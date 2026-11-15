package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONObject

class WeatherRepository(private val context: Context) {

  fun hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
  }

  fun fetchWeatherJson(manualCity: String? = null): String {
    if (!isNetworkAvailable()) {
      return JSONObject().apply {
        put("ok", false)
        put("reason", "offline")
      }.toString()
    }

    return try {
      val coords = if (!manualCity.isNullOrBlank()) {
        geocodeCity(manualCity.trim())
      } else {
        if (!hasLocationPermission()) {
          return JSONObject().apply {
            put("ok", false)
            put("reason", "denied")
          }.toString()
        }
        resolveDeviceCoordinates()
      }

      if (coords == null) {
        return JSONObject().apply {
          put("ok", false)
          put("reason", "unavailable")
        }.toString()
      }

      val (lat, lon, resolvedCity) = coords
      val forecastUrl =
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
          "&current=temperature_2m,weather_code,is_day"
      val raw = httpGet(forecastUrl) ?: return errorJson("unavailable")
      val root = JSONObject(raw)
      val current = root.optJSONObject("current") ?: return errorJson("unavailable")
      val tempC = current.optDouble("temperature_2m", Double.NaN)
      val code = current.optInt("weather_code", 0)
      val isDay = current.optInt("is_day", 1)
      if (tempC.isNaN()) return errorJson("unavailable")

      JSONObject().apply {
        put("ok", true)
        put("tempC", tempC)
        put("code", code)
        put("isDay", isDay)
        put("city", resolvedCity)
      }.toString()
    } catch (_: Exception) {
      errorJson("unavailable")
    }
  }

  @SuppressLint("MissingPermission")
  private fun resolveDeviceCoordinates(): Triple<Double, Double, String>? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    var best: Location? = null
    if (lm != null) {
      val providers = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
      )
      for (p in providers) {
        try {
          if (!lm.isProviderEnabled(p)) continue
          val loc = lm.getLastKnownLocation(p) ?: continue
          if (best == null || loc.time > best.time) {
            best = loc
          }
        } catch (_: Exception) {}
      }
    }

    if (best != null) {
      val city = reverseGeocodeCity(best.latitude, best.longitude) ?: "Local"
      return Triple(best.latitude, best.longitude, city)
    }

    // Fallback for Android TV boxes lacking a location provider (only called after permission grant)
    val ipRaw = httpGet("https://ipwho.is/") ?: return null
    val ipJson = JSONObject(ipRaw)
    if (!ipJson.optBoolean("success", false)) return null
    val lat = ipJson.optDouble("latitude", Double.NaN)
    val lon = ipJson.optDouble("longitude", Double.NaN)
    if (lat.isNaN() || lon.isNaN()) return null
    val city = ipJson.optString("city").ifBlank { ipJson.optString("country", "Local") }
    return Triple(lat, lon, city)
  }

  private fun geocodeCity(cityName: String): Triple<Double, Double, String>? {
    val encoded = URLEncoder.encode(cityName, "UTF-8")
    val raw = httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json")
      ?: return null
    val results = JSONObject(raw).optJSONArray("results") ?: return null
    if (results.length() == 0) return null
    val first = results.getJSONObject(0)
    val lat = first.optDouble("latitude", Double.NaN)
    val lon = first.optDouble("longitude", Double.NaN)
    if (lat.isNaN() || lon.isNaN()) return null
    val name = first.optString("name", cityName)
    return Triple(lat, lon, name)
  }

  @Suppress("DEPRECATION")
  private fun reverseGeocodeCity(lat: Double, lon: Double): String? {
    return try {
      if (!Geocoder.isPresent()) return null
      val list = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
      val addr = list?.firstOrNull() ?: return null
      addr.locality ?: addr.subAdminArea ?: addr.adminArea
    } catch (_: Exception) {
      null
    }
  }

  private fun isNetworkAvailable(): Boolean {
    return try {
      val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
      val net = cm.activeNetwork ?: return false
      val caps = cm.getNetworkCapabilities(net) ?: return false
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
      true
    }
  }

  private fun httpGet(urlStr: String): String? {
    var conn: HttpURLConnection? = null
    return try {
      conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 5000
        readTimeout = 5000
      }
      if (conn.responseCode !in 200..299) return null
      BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
    } catch (_: Exception) {
      null
    } finally {
      conn?.disconnect()
    }
  }

  private fun errorJson(reason: String): String {
    return JSONObject().apply {
      put("ok", false)
      put("reason", reason)
    }.toString()
  }
}
