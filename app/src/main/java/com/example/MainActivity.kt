package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.LruCache
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {

  private var webViewRef: WebView? = null
  @Volatile private var cachedStateJson: String = "{\"prefs\":{},\"favorites\":[],\"hidden\":[],\"bloatSel\":[],\"apps\":[]}"
  @Volatile private var pendingWeatherCity: String = ""
  private lateinit var lanServer: LanCustomizerServer
  private lateinit var weatherRepo: WeatherRepository
  private lateinit var geminiAssistant: TvGeminiAssistant
  private val weatherExecutor = Executors.newSingleThreadExecutor()
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private val bannerCache = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ByteArray): Int = value.size
  }

  private val locationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { _ ->
    fetchWeatherToWebView(pendingWeatherCity)
  }

  private val roleHomeLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { _ ->
    pushLauncherStatus()
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureAppCheck(this, intent)

    geminiAssistant = TvGeminiAssistant(lifecycleScope) { json ->
      runOnUiThread {
        val quoted = JSONObject.quote(json)
        webViewRef?.evaluateJavascript(
          "window.onGeminiUpdate && window.onGeminiUpdate(JSON.parse($quoted))",
          null
        )
      }
    }

    weatherRepo = WeatherRepository(applicationContext)
    lanServer = LanCustomizerServer(
      port = 8787,
      getStateJson = { cachedStateJson },
      onApplyPayload = { payload ->
        runOnUiThread {
          val quoted = JSONObject.quote(payload.toString())
          webViewRef?.evaluateJavascript(
            "window.applyRemoteUpdate && window.applyRemoteUpdate(JSON.parse($quoted))",
            null
          )
        }
      }
    )
    lanServer.start()

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
    )
    hideSystemBars()

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          val wv = webViewRef ?: return
          wv.evaluateJavascript("window.handleAndroidBack && window.handleAndroidBack()", null)
        }
      }
    )

    setContent {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(ComposeColor(0xFF060609))
      ) {
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { context ->
            WebView(context).apply {
              layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
              )
              setBackgroundColor(Color.parseColor("#060609"))
              isFocusable = true
              isFocusableInTouchMode = true
              overScrollMode = WebView.OVER_SCROLL_NEVER

              settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                  safeBrowsingEnabled = false
                }
              }

              webChromeClient = WebChromeClient()
              addJavascriptInterface(TVBridge(context), "TVBridge")

              webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                  view: WebView?,
                  request: WebResourceRequest?
                ): WebResourceResponse? {
                  val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
                  if (uri.host == "tv-banner.local") {
                    val pkg = uri.getQueryParameter("pkg")
                    if (!pkg.isNullOrBlank()) {
                      val bytes = getBannerPngBytes(pkg)
                      if (bytes != null) {
                        return WebResourceResponse("image/png", null, ByteArrayInputStream(bytes))
                      }
                    }
                    return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                  }
                  if (uri.host == "tv-home.local" && uri.path == "/yt") {
                    val vid = uri.getQueryParameter("v")?.replace(Regex("[^a-zA-Z0-9_-]"), "").orEmpty()
                    if (vid.isNotEmpty()) {
                      val mute = if (uri.getQueryParameter("mute") == "0") "0" else "1"
                      val html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><meta name='referrer' content='strict-origin-when-cross-origin'><style>html,body,iframe{margin:0;padding:0;width:100%;height:100%;border:0;background:#000;overflow:hidden}</style></head><body><iframe referrerpolicy='strict-origin-when-cross-origin' src='https://www.youtube-nocookie.com/embed/$vid?autoplay=1&mute=$mute&controls=1&rel=0&playsinline=1' allow='autoplay; encrypted-media; picture-in-picture' allowfullscreen></iframe></body></html>"
                      return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                    }
                  }
                  return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(
                  view: WebView?,
                  request: WebResourceRequest?
                ): Boolean {
                  val url = request?.url?.toString() ?: return false
                  if (url.startsWith("intent://")) {
                    handleIntentUrl(url)
                    return true
                  }
                  return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                  super.onPageFinished(view, url)
                  requestInitialWeather()
                  pushNetworkStatus()
                  pushLauncherStatus()
                }

                override fun onRenderProcessGone(
                  view: WebView?,
                  detail: RenderProcessGoneDetail?
                ): Boolean {
                  view?.loadUrl("file:///android_asset/tv_home.html")
                  return true
                }
              }

              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
              }
              loadUrl("file:///android_asset/tv_home.html")
              requestFocus()
              webViewRef = this
            }
          }
        )
      }
    }
    registerNetworkMonitor()
  }

  override fun onResume() {
    super.onResume()
    webViewRef?.onResume()
    hideSystemBars()
    pushLauncherStatus()
  }

  override fun onPause() {
    webViewRef?.onPause()
    super.onPause()
  }

  override fun onDestroy() {
    if (::geminiAssistant.isInitialized) geminiAssistant.cancel()
    if (::lanServer.isInitialized) lanServer.stop()
    unregisterNetworkMonitor()
    weatherExecutor.shutdownNow()
    webViewRef?.apply {
      stopLoading()
      webChromeClient = null
      destroy()
    }
    webViewRef = null
    super.onDestroy()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      hideSystemBars()
      webViewRef?.requestFocus()
    }
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val wv = webViewRef
    if (wv != null && event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
      val mapped = KeyEvent(
        event.downTime,
        event.eventTime,
        event.action,
        KeyEvent.KEYCODE_ENTER,
        event.repeatCount,
        event.metaState,
        event.deviceId,
        event.scanCode,
        event.flags
      )
      return wv.dispatchKeyEvent(mapped)
    }
    if (wv != null && event.action == KeyEvent.ACTION_UP) {
      val remoteCmd = when (event.keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> "ambient"
        KeyEvent.KEYCODE_SEARCH -> "search"
        KeyEvent.KEYCODE_CHANNEL_UP -> "nextClip"
        KeyEvent.KEYCODE_CHANNEL_DOWN -> "prevClip"
        else -> null
      }
      if (remoteCmd != null) {
        wv.evaluateJavascript("window.applyRemoteUpdate && window.applyRemoteUpdate({type:'remote',key:'$remoteCmd'})", null)
        return true
      }
    }
    return super.dispatchKeyEvent(event)
  }

  private fun requestInitialWeather() {
    if (weatherRepo.hasLocationPermission() || pendingWeatherCity.isNotBlank()) {
      fetchWeatherToWebView(pendingWeatherCity)
    } else {
      locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
  }

  private fun fetchWeatherToWebView(city: String) {
    weatherExecutor.execute {
      val json = weatherRepo.fetchWeatherJson(city)
      runOnUiThread {
        val quoted = JSONObject.quote(json)
        webViewRef?.evaluateJavascript(
          "window.onWeatherUpdate && window.onWeatherUpdate(JSON.parse($quoted))",
          null
        )
      }
    }
  }

  private fun hideSystemBars() {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior =
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
  }

  private fun handleIntentUrl(url: String) {
    try {
      val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
      val pkg = intent.`package`
      if (!pkg.isNullOrBlank()) {
        launchPackage(pkg)
      } else {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
      }
    } catch (_: Exception) {
      Toast.makeText(this, "App not installed on this device", Toast.LENGTH_SHORT).show()
    }
  }

  private fun launchPackage(packageName: String): Boolean {
    val pm = packageManager
    val launchIntent = pm.getLeanbackLaunchIntentForPackage(packageName)
      ?: pm.getLaunchIntentForPackage(packageName)
    return if (launchIntent != null) {
      launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      try {
        startActivity(launchIntent)
        true
      } catch (_: Exception) {
        Toast.makeText(this, "Unable to open $packageName", Toast.LENGTH_SHORT).show()
        false
      }
    } else {
      Toast.makeText(this, "App not installed: $packageName", Toast.LENGTH_SHORT).show()
      false
    }
  }

  inner class TVBridge(private val context: Context) {

    @JavascriptInterface
    fun getApps(): String {
      return try {
        val pm = context.packageManager
        val knownTvPackages = setOf(
          "com.google.android.youtube.tv",
          "com.google.android.youtube",
          "com.teamsmart.videomanager.tv",
          "org.schabi.newpipe",
          "com.netflix.ninja",
          "com.netflix.mediaclient",
          "com.amazon.amazonvideo.livingroom",
          "com.amazon.avod.thirdpartyclient",
          "in.startv.hotstar",
          "in.startv.hotstar.dplus",
          "com.disney.disneyplus",
          "com.jio.media.stb.ondemand",
          "com.jio.media.ondemand",
          "com.sonyliv",
          "com.graymatrix.did",
          "com.plexapp.android",
          "org.xbmc.kodi",
          "org.videolan.vlc",
          "com.mxtech.videoplayer.ad",
          "com.spotify.tv.android",
          "tv.twitch.android.app",
          "ar.tvplayer.tv",
          "com.tubitv",
          "tv.pluto.android",
          "com.android.vending",
          "com.google.android.videos",
          "com.google.android.tv",
          "com.google.android.tvlauncher",
          "com.android.tv.settings",
          "com.hisilicon.tvui",
          "com.android.tv"
        )
        val knownTvGamePackages = setOf(
          "com.google.android.play.games",
          "com.vectorunit.purple.googleplay",
          "com.vectorunit.yellow",
          "com.hipsterwhale.crossy",
          "com.retroarch",
          "com.nvidia.tegrazone3",
          "com.gameloft.android.ANMP.GloftA8HM",
          "com.kiloo.subwaysurf",
          "com.airconsole.controller",
          "com.mojang.minecraftpe",
          "com.valvesoftware.steamlink",
          "com.limelight",
          "com.nvidia.geforcenow"
        )
        val phoneOnlyPackages = setOf(
          "com.android.dialer",
          "com.google.android.dialer",
          "com.android.contacts",
          "com.google.android.contacts",
          "com.android.messaging",
          "com.android.mms",
          "com.google.android.apps.messaging",
          "com.android.camera2",
          "com.android.calculator2",
          "com.google.android.calculator",
          "com.android.deskclock",
          "com.google.android.deskclock",
          "com.android.calendar",
          "com.google.android.calendar",
          "com.android.phone"
        )

        data class AppEntry(
          val label: String,
          val tv: Boolean,
          val game: Boolean,
          val builtin: Boolean
        )

        val seen = linkedMapOf<String, AppEntry>()

        fun collect(category: String, forceTv: Boolean) {
          val query = Intent(Intent.ACTION_MAIN).addCategory(category)
          @Suppress("DEPRECATION")
          val infos = pm.queryIntentActivities(query, PackageManager.MATCH_ALL)
          for (ri in infos) {
            val actInfo = ri.activityInfo ?: continue
            val pkg = actInfo.packageName ?: continue
            if (pkg == context.packageName) continue
            val ai = actInfo.applicationInfo
            val isBuiltin = ai != null && (
              (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
              )
            val isGame = (ai != null && (
              (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0 ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ai.category == ApplicationInfo.CATEGORY_GAME)
              )) || knownTvGamePackages.contains(pkg)
            val isTvPrefix = pkg.startsWith("com.hisilicon.") ||
              pkg.startsWith("com.google.android.tv") ||
              pkg.startsWith("com.android.tv")
            val isPhoneOnly = phoneOnlyPackages.contains(pkg)
            val isTv = forceTv || (!isPhoneOnly && (knownTvPackages.contains(pkg) || knownTvGamePackages.contains(pkg) || isTvPrefix))

            val existing = seen[pkg]
            if (existing == null) {
              val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
              seen[pkg] = AppEntry(label, isTv, isGame, isBuiltin)
            } else {
              seen[pkg] = existing.copy(
                tv = existing.tv || isTv,
                game = existing.game || isGame,
                builtin = existing.builtin || isBuiltin
              )
            }
          }
        }

        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        collect(Intent.CATEGORY_LAUNCHER, false)

        val arr = JSONArray()
        seen.entries
          .sortedBy { it.value.label.lowercase() }
          .forEach { (pkg, entry) ->
            val hasLeanback = entry.tv || try { pm.getLeanbackLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }
            arr.put(
              JSONObject().apply {
                put("label", entry.label)
                put("package", pkg)
                put("tv", hasLeanback)
                put("game", entry.game)
                put("builtin", entry.builtin)
              }
            )
          }
        arr.toString()
      } catch (_: Exception) {
        "[]"
      }
    }

    @JavascriptInterface
    fun launch(packageName: String): Boolean {
      runOnUiThread { launchPackage(packageName) }
      return true
    }

    @JavascriptInterface
    fun copyText(text: String): Boolean {
      return try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
          ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("TV Home", text))
        true
      } catch (_: Exception) {
        false
      }
    }

    @JavascriptInterface
    fun getLanUrl(): String {
      return try {
        lanServer.getLanUrl()
      } catch (_: Exception) {
        ""
      }
    }

    @JavascriptInterface
    fun syncState(json: String) {
      if (json.isNotBlank()) {
        cachedStateJson = json
      }
    }

    @JavascriptInterface
    fun requestWeather(city: String) {
      pendingWeatherCity = city.trim()
      runOnUiThread {
        if (pendingWeatherCity.isNotBlank() || weatherRepo.hasLocationPermission()) {
          fetchWeatherToWebView(pendingWeatherCity)
        } else {
          locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
      }
    }

    @JavascriptInterface
    fun getNetworkStatus(): String {
      return buildNetworkStatusJson()
    }

    @JavascriptInterface
    fun openInputs(): Boolean {
      runOnUiThread { launchTvInputs() }
      return true
    }

    @JavascriptInterface
    fun openLiveTv(source: String): Boolean {
      runOnUiThread {
        startActivity(
          Intent(this@MainActivity, LiveTvActivity::class.java).apply {
            putExtra(LiveTvActivity.EXTRA_SOURCE, source)
          }
        )
      }
      return true
    }

    @JavascriptInterface
    fun selectInput(source: String): Boolean {
      runOnUiThread { switchTvInput(source) }
      return true
    }

    @JavascriptInterface
    fun getStartupEnabled(): Boolean {
      return context.getSharedPreferences("tvh_native", Context.MODE_PRIVATE)
        .getBoolean("startup_on_boot", true)
    }

    @JavascriptInterface
    fun setStartupEnabled(enabled: Boolean): Boolean {
      context.getSharedPreferences("tvh_native", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("startup_on_boot", enabled)
        .apply()
      return true
    }

    @JavascriptInterface
    fun openHomeSettings(): Boolean {
      runOnUiThread { requestSetDefaultLauncher() }
      return true
    }

    @JavascriptInterface
    fun isDefaultLauncher(): Boolean {
      return isHomeLauncherDefault()
    }

    @JavascriptInterface
    fun requestDefaultLauncher(): Boolean {
      runOnUiThread { requestSetDefaultLauncher() }
      return true
    }

    @JavascriptInterface
    fun askGemini(prompt: String, appsSummary: String): Boolean {
      runOnUiThread {
        if (::geminiAssistant.isInitialized) {
          geminiAssistant.ask(prompt, appsSummary)
        }
      }
      return true
    }

    @JavascriptInterface
    fun cancelGemini(): Boolean {
      runOnUiThread {
        if (::geminiAssistant.isInitialized) geminiAssistant.cancel()
      }
      return true
    }
  }

  private fun isHomeLauncherDefault(): Boolean {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val rm = getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleHeld(RoleManager.ROLE_HOME)) return true
      }
      val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
      @Suppress("DEPRECATION")
      val ri = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
      ri?.activityInfo?.packageName == packageName
    } catch (_: Exception) {
      false
    }
  }

  private fun pushLauncherStatus() {
    val isDef = isHomeLauncherDefault()
    runOnUiThread {
      webViewRef?.evaluateJavascript(
        "window.onLauncherStatus && window.onLauncherStatus($isDef)",
        null
      )
    }
  }

  private fun requestSetDefaultLauncher() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      try {
        val rm = getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
          roleHomeLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
          return
        }
      } catch (_: Exception) {}
    }
    launchHomeSettings()
  }

  private fun launchHomeSettings() {
    val candidates = listOf(
      Intent(Settings.ACTION_HOME_SETTINGS),
      Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
      Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
      Intent(Settings.ACTION_SETTINGS)
    )
    for (intent in candidates) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      if (intent.resolveActivity(packageManager) != null) {
        try {
          startActivity(intent)
          return
        } catch (_: Exception) {}
      }
    }
    Toast.makeText(this, "Android Home settings unavailable", Toast.LENGTH_SHORT).show()
  }

  private fun switchTvInput(source: String) {
    val key = source.trim().lowercase()
    if (key == "home") {
      requestSetDefaultLauncher()
      return
    }
    if (key == "system") {
      launchTvInputs()
      return
    }
    if (key == "live") {
      startActivity(
        Intent(this, LiveTvActivity::class.java).putExtra(LiveTvActivity.EXTRA_SOURCE, "live")
      )
      return
    }
    try {
      val mgr = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
      val list = mgr?.tvInputList.orEmpty()
      if (list.isNotEmpty()) {
        startActivity(
          Intent(this, LiveTvActivity::class.java).putExtra(LiveTvActivity.EXTRA_SOURCE, key)
        )
        return
      }
    } catch (_: Exception) {}
    launchTvInputs()
  }

  private fun launchTvInputs() {
    val pm = packageManager
    val candidates = listOf(
      Intent("android.media.tv.action.SETUP_INPUTS"),
      Intent(Intent.ACTION_VIEW, Uri.parse("content://android.media.tv/channel")),
      pm.getLeanbackLaunchIntentForPackage("com.hisilicon.tvui"),
      pm.getLaunchIntentForPackage("com.hisilicon.tvui"),
      pm.getLeanbackLaunchIntentForPackage("com.android.tv"),
      Intent(Settings.ACTION_SETTINGS)
    )
    for (intent in candidates) {
      if (intent == null) continue
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      if (intent.resolveActivity(pm) != null) {
        try {
          startActivity(intent)
          return
        } catch (_: Exception) {}
      }
    }
    Toast.makeText(this, "TV Inputs not exposed by this firmware", Toast.LENGTH_SHORT).show()
  }

  private fun getBannerPngBytes(pkg: String): ByteArray? {
    bannerCache.get(pkg)?.let { return it }
    return try {
      val pm = packageManager
      var bannerDrawable: Drawable? = null

      val leanbackIntent = pm.getLeanbackLaunchIntentForPackage(pkg)
      val component = leanbackIntent?.component
      if (component != null) {
        bannerDrawable = try { pm.getActivityBanner(component) } catch (_: Exception) { null }
      }
      if (bannerDrawable == null) {
        bannerDrawable = try { pm.getApplicationBanner(pkg) } catch (_: Exception) { null }
      }

      val w = 320
      val h = 180
      val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(outBmp)

      if (bannerDrawable != null) {
        bannerDrawable.setBounds(0, 0, w, h)
        bannerDrawable.draw(canvas)
      } else {
        val iconDrawable = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null } ?: return null
        val iconBmp = drawableToBitmap(iconDrawable, 96, 96)
        val accent = extractAccentColor(iconBmp)
        val topCol = blendDark(accent, 0.42f)
        val botCol = blendDark(accent, 0.14f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), topCol, botCol, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val left = (w - iconBmp.width) / 2f
        val top = (h - iconBmp.height) / 2f
        canvas.drawBitmap(iconBmp, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
      }

      val bos = ByteArrayOutputStream()
      outBmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
      val bytes = bos.toByteArray()
      bannerCache.put(pkg, bytes)
      bytes
    } catch (_: Exception) {
      null
    }
  }

  private fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
    if (drawable is BitmapDrawable) {
      val src = drawable.bitmap
      if (src != null && !src.isRecycled && src.width > 0 && src.height > 0) {
        return Bitmap.createScaledBitmap(src, width, height, true)
      }
    }
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(c)
    return bmp
  }

  private fun extractAccentColor(bmp: Bitmap): Int {
    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0L
    val step = 4
    for (y in 0 until bmp.height step step) {
      for (x in 0 until bmp.width step step) {
        val p = bmp.getPixel(x, y)
        val a = Color.alpha(p)
        if (a < 180) continue
        val r = Color.red(p)
        val g = Color.green(p)
        val b = Color.blue(p)
        if (r + g + b < 45 || (r > 240 && g > 240 && b > 240)) continue
        rSum += r
        gSum += g
        bSum += b
        count++
      }
    }
    if (count == 0L) return Color.rgb(64, 72, 136)
    return Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
  }

  private fun blendDark(color: Int, factor: Float): Int {
    val r = (Color.red(color) * factor).toInt().coerceIn(8, 255)
    val g = (Color.green(color) * factor).toInt().coerceIn(8, 255)
    val b = (Color.blue(color) * factor).toInt().coerceIn(12, 255)
    return Color.rgb(r, g, b)
  }

  private fun registerNetworkMonitor() {
    try {
      val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
      val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = pushNetworkStatus()
        override fun onLost(network: Network) = pushNetworkStatus()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = pushNetworkStatus()
      }
      networkCallback = cb
      cm.registerDefaultNetworkCallback(cb)
    } catch (_: Exception) {}
  }

  private fun unregisterNetworkMonitor() {
    try {
      val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
      networkCallback?.let { cm.unregisterNetworkCallback(it) }
    } catch (_: Exception) {}
  }

  private fun pushNetworkStatus() {
    val json = buildNetworkStatusJson()
    runOnUiThread {
      val quoted = JSONObject.quote(json)
      webViewRef?.evaluateJavascript(
        "window.onNetworkUpdate && window.onNetworkUpdate(JSON.parse($quoted))",
        null
      )
    }
  }

  @Suppress("DEPRECATION")
  private fun buildNetworkStatusJson(): String {
    return try {
      val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      val net = cm?.activeNetwork
      val caps = if (net != null) cm.getNetworkCapabilities(net) else null
      val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
      if (!online) {
        return JSONObject().apply {
          put("online", false)
          put("bars", 0)
          put("quality", "Offline")
        }.toString()
      }

      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
        return JSONObject().apply {
          put("online", true)
          put("bars", 4)
          put("quality", "Ethernet")
        }.toString()
      }

      if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        var rssi = Int.MIN_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val info = caps.transportInfo as? WifiInfo
          if (info != null && info.rssi > -127 && info.rssi < 0) {
            rssi = info.rssi
          } else if (caps.signalStrength != Int.MIN_VALUE) {
            rssi = caps.signalStrength
          }
        }
        if (rssi == Int.MIN_VALUE) {
          val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
          val info = wm?.connectionInfo
          if (info != null && info.rssi > -127 && info.rssi < 0) {
            rssi = info.rssi
          }
        }
        val (bars, quality) = when {
          rssi == Int.MIN_VALUE -> 3 to "Wi-Fi"
          rssi >= -55 -> 4 to "Strong"
          rssi >= -67 -> 3 to "Good"
          rssi >= -78 -> 2 to "Fair"
          else -> 1 to "Weak"
        }
        return JSONObject().apply {
          put("online", true)
          put("bars", bars)
          put("quality", quality)
        }.toString()
      }

      JSONObject().apply {
        put("online", true)
        put("bars", 3)
        put("quality", "Connected")
      }.toString()
    } catch (_: Exception) {
      JSONObject().apply {
        put("online", true)
        put("bars", 3)
        put("quality", "Online")
      }.toString()
    }
  }
}
