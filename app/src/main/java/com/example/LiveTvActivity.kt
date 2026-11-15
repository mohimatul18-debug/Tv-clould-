package com.example

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

data class LiveSourceItem(
  val id: String,
  val label: String,
  val typeLabel: String,
  val isPassthrough: Boolean
)

data class LiveChannelItem(
  val id: Long,
  val inputId: String,
  val number: String,
  val name: String,
  val uri: Uri
)

class LiveTvActivity : ComponentActivity() {

  companion object {
    const val EXTRA_SOURCE = "extra_source"
  }

  private var tvViewRef: TvView? = null
  private var sources by mutableStateOf<List<LiveSourceItem>>(emptyList())
  private var channels by mutableStateOf<List<LiveChannelItem>>(emptyList())
  private var selectedSourceIdx by mutableIntStateOf(0)
  private var selectedChannelIdx by mutableIntStateOf(0)
  private var statusMessage by mutableStateOf("Initializing Google TV Live Tuner...")
  private var nowPlayingTitle by mutableStateOf("")
  private var hudVisible by mutableStateOf(true)
  private var focusZone by mutableIntStateOf(0) // 0: sources, 1: channels
  private var hudTrigger by mutableIntStateOf(0)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    hideSystemBars()

    loadSourcesAndChannels()
    val requested = intent.getStringExtra(EXTRA_SOURCE)?.lowercase().orEmpty()
    applyInitialSelection(requested)

    setContent {
      MaterialTheme {
        LiveTvScreen(
          sources = sources,
          channels = currentSourceChannels(),
          selectedSourceIdx = selectedSourceIdx,
          selectedChannelIdx = selectedChannelIdx,
          focusZone = focusZone,
          hudVisible = hudVisible,
          statusMessage = statusMessage,
          nowPlayingTitle = nowPlayingTitle,
          onSelectSource = { idx ->
            selectedSourceIdx = idx
            selectedChannelIdx = 0
            focusZone = 0
            tuneCurrentSelection()
            bumpHud()
          },
          onSelectChannel = { idx ->
            selectedChannelIdx = idx
            focusZone = 1
            tuneCurrentSelection()
            bumpHud()
          },
          onOpenSystemSetup = { openSystemTvSetup() },
          onExit = { finish() }
        )
      }
    }
  }

  override fun onDestroy() {
    tvViewRef?.reset()
    tvViewRef = null
    super.onDestroy()
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_UP) {
      bumpHud()
      when (event.keyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
          if (hudVisible) {
            hudVisible = false
          } else {
            finish()
          }
          return true
        }
        KeyEvent.KEYCODE_CHANNEL_UP -> {
          stepChannel(1)
          return true
        }
        KeyEvent.KEYCODE_CHANNEL_DOWN -> {
          stepChannel(-1)
          return true
        }
        KeyEvent.KEYCODE_DPAD_UP -> {
          focusZone = 0
          return true
        }
        KeyEvent.KEYCODE_DPAD_DOWN -> {
          if (currentSourceChannels().isNotEmpty()) focusZone = 1
          return true
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> {
          if (focusZone == 0 && sources.isNotEmpty()) {
            selectedSourceIdx = (selectedSourceIdx - 1 + sources.size) % sources.size
            selectedChannelIdx = 0
          } else {
            stepChannel(-1)
          }
          return true
        }
        KeyEvent.KEYCODE_DPAD_RIGHT -> {
          if (focusZone == 0 && sources.isNotEmpty()) {
            selectedSourceIdx = (selectedSourceIdx + 1) % sources.size
            selectedChannelIdx = 0
          } else {
            stepChannel(1)
          }
          return true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
          if (!hudVisible) {
            hudVisible = true
          } else {
            tuneCurrentSelection()
          }
          return true
        }
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
          hudVisible = !hudVisible
          return true
        }
      }
    }
    return super.dispatchKeyEvent(event)
  }

  private fun bumpHud() {
    hudVisible = true
    hudTrigger++
  }

  private fun stepChannel(delta: Int) {
    val chList = currentSourceChannels()
    if (chList.isEmpty()) {
      if (sources.isNotEmpty()) {
        selectedSourceIdx = (selectedSourceIdx + delta + sources.size) % sources.size
        tuneCurrentSelection()
      }
      return
    }
    selectedChannelIdx = (selectedChannelIdx + delta + chList.size) % chList.size
    tuneCurrentSelection()
  }

  private fun currentSourceChannels(): List<LiveChannelItem> {
    val src = sources.getOrNull(selectedSourceIdx) ?: return emptyList()
    return channels.filter { it.inputId == src.id }
  }

  private fun loadSourcesAndChannels() {
    val discoveredSources = mutableListOf<LiveSourceItem>()
    try {
      val mgr = getSystemService(TV_INPUT_SERVICE) as? TvInputManager
      val inputs = mgr?.tvInputList.orEmpty()
      for (info in inputs) {
        if (info.isHidden(this)) continue
        val label = info.loadLabel(this)?.toString()?.trim().orEmpty().ifEmpty { info.id }
        val kind = when (info.type) {
          TvInputInfo.TYPE_TUNER -> "TUNER / SAT / DTV"
          TvInputInfo.TYPE_HDMI -> "HDMI"
          TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_COMPONENT, TvInputInfo.TYPE_SCART -> "AV / LINE"
          TvInputInfo.TYPE_DISPLAY_PORT, TvInputInfo.TYPE_DVI, TvInputInfo.TYPE_VGA -> "DISPLAY"
          else -> "TIF SOURCE"
        }
        discoveredSources.add(
          LiveSourceItem(
            id = info.id,
            label = label,
            typeLabel = kind,
            isPassthrough = info.isPassthroughInput
          )
        )
      }
    } catch (_: Exception) {}

    val discoveredChannels = mutableListOf<LiveChannelItem>()
    try {
      val projection = arrayOf(
        TvContract.Channels._ID,
        TvContract.Channels.COLUMN_INPUT_ID,
        TvContract.Channels.COLUMN_DISPLAY_NUMBER,
        TvContract.Channels.COLUMN_DISPLAY_NAME
      )
      contentResolver.query(TvContract.Channels.CONTENT_URI, projection, null, null, null)?.use { c ->
        val idCol = c.getColumnIndex(TvContract.Channels._ID)
        val inCol = c.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID)
        val numCol = c.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NUMBER)
        val nameCol = c.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME)
        while (c.moveToNext()) {
          val id = if (idCol >= 0) c.getLong(idCol) else continue
          val inputId = if (inCol >= 0) c.getString(inCol).orEmpty() else continue
          val num = if (numCol >= 0) c.getString(numCol).orEmpty() else ""
          val name = if (nameCol >= 0) c.getString(nameCol).orEmpty().ifBlank { "Channel $id" } else "Channel $id"
          discoveredChannels.add(
            LiveChannelItem(
              id = id,
              inputId = inputId,
              number = num,
              name = name,
              uri = TvContract.buildChannelUri(id)
            )
          )
        }
      }
    } catch (_: Exception) {}

    sources = discoveredSources
    channels = discoveredChannels
    if (discoveredSources.isEmpty()) {
      statusMessage = "No hardware TV tuner, Satellite, or HDMI TIF inputs reported by this firmware/emulator."
    }
  }

  private fun applyInitialSelection(requested: String) {
    if (sources.isEmpty()) return
    val idx = when (requested) {
      "satellite" -> sources.indexOfFirst {
        it.label.lowercase().contains("sat") || it.label.lowercase().contains("dish") ||
          it.typeLabel.contains("TUNER")
      }
      "dtv" -> sources.indexOfFirst { it.typeLabel.contains("TUNER") }
      "hdmi1" -> sources.indexOfFirst {
        it.typeLabel == "HDMI" && (it.label.lowercase().contains("1") || !it.label.lowercase().contains("2"))
      }
      "hdmi2" -> {
        val explicit = sources.indexOfFirst { it.typeLabel == "HDMI" && it.label.lowercase().contains("2") }
        if (explicit >= 0) explicit else sources.withIndex().filter { it.value.typeLabel == "HDMI" }.getOrNull(1)?.index ?: -1
      }
      "av", "line" -> sources.indexOfFirst { it.typeLabel.contains("AV") }
      else -> 0
    }
    selectedSourceIdx = if (idx >= 0) idx else 0
    selectedChannelIdx = 0
  }

  private fun tuneCurrentSelection() {
    val tv = tvViewRef ?: return
    val src = sources.getOrNull(selectedSourceIdx) ?: return
    val srcChannels = currentSourceChannels()
    val ch = srcChannels.getOrNull(selectedChannelIdx)
    val uri = when {
      ch != null -> ch.uri
      src.isPassthrough -> TvContract.buildChannelUriForPassthroughInput(src.id)
      else -> TvContract.buildChannelsUriForInput(src.id)
    }
    nowPlayingTitle = ch?.let {
      val numPrefix = if (it.number.isNotBlank()) "${it.number} · " else ""
      numPrefix + it.name
    } ?: src.label
    statusMessage = "Tuning ${src.label}..."
    try {
      tv.reset()
      tv.tune(src.id, uri)
      if (ch != null) queryNowPlayingEpg(ch.id)
    } catch (e: Exception) {
      statusMessage = "Unable to tune ${src.label}: ${e.localizedMessage ?: "signal unavailable"}"
    }
  }

  private fun queryNowPlayingEpg(channelId: Long) {
    try {
      val now = System.currentTimeMillis()
      val uri = TvContract.buildProgramsUriForChannel(channelId, now, now + 60_000L)
      contentResolver.query(uri, arrayOf(TvContract.Programs.COLUMN_TITLE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
          val t = c.getString(0)
          if (!t.isNullOrBlank()) nowPlayingTitle = "$nowPlayingTitle — $t"
        }
      }
    } catch (_: Exception) {}
  }

  private fun openSystemTvSetup() {
    val candidates = listOf(
      Intent("android.media.tv.action.SETUP_INPUTS"),
      packageManager.getLeanbackLaunchIntentForPackage("com.hisilicon.tvui"),
      packageManager.getLeanbackLaunchIntentForPackage("com.android.tv"),
      Intent(Settings.ACTION_SETTINGS)
    )
    for (i in candidates) {
      if (i == null) continue
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      if (i.resolveActivity(packageManager) != null) {
        try {
          startActivity(i)
          return
        } catch (_: Exception) {}
      }
    }
    Toast.makeText(this, "System TV input setup unavailable", Toast.LENGTH_SHORT).show()
  }

  private fun hideSystemBars() {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
  }

  @Composable
  private fun LiveTvScreen(
    sources: List<LiveSourceItem>,
    channels: List<LiveChannelItem>,
    selectedSourceIdx: Int,
    selectedChannelIdx: Int,
    focusZone: Int,
    hudVisible: Boolean,
    statusMessage: String,
    nowPlayingTitle: String,
    onSelectSource: (Int) -> Unit,
    onSelectChannel: (Int) -> Unit,
    onOpenSystemSetup: () -> Unit,
    onExit: () -> Unit
  ) {
    LaunchedEffect(hudTrigger) {
      if (hudVisible && sources.isNotEmpty()) {
        delay(6000)
        this@LiveTvActivity.hudVisible = false
      }
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .clickable { bumpHud() }
    ) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          TvView(ctx).apply {
            setBackgroundColor(AndroidColor.BLACK)
            setCallback(object : TvView.TvInputCallback() {
              override fun onVideoAvailable(inputId: String?) {
                runOnUiThread {
                  this@LiveTvActivity.statusMessage = "Live · ${sources.getOrNull(selectedSourceIdx)?.label ?: inputId.orEmpty()}"
                }
              }

              override fun onVideoUnavailable(inputId: String?, reason: Int) {
                runOnUiThread {
                  val reasonTxt = when (reason) {
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> "Weak or no antenna/satellite signal"
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING -> "Tuning channel..."
                    TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> "Buffering stream..."
                    else -> "Waiting for video input"
                  }
                  this@LiveTvActivity.statusMessage = reasonTxt
                }
              }
            })
            tvViewRef = this
            tuneCurrentSelection()
          }
        }
      )

      if (sources.isEmpty()) {
        Column(
          modifier = Modifier
            .align(Alignment.Center)
            .padding(32.dp)
            .background(Color(0xCC111624), RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(24.dp))
            .padding(28.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            "Google TV Live Player",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold
          )
          Spacer(Modifier.height(10.dp))
          Text(
            statusMessage,
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 14.sp
          )
          Spacer(Modifier.height(18.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
              onClick = onOpenSystemSetup,
              modifier = Modifier.testTag("live_tv_setup_btn")
            ) { Text("TV Source Setup") }
            Button(
              onClick = onExit,
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D3348)),
              modifier = Modifier.testTag("live_tv_back_btn")
            ) { Text("Back to TV Home") }
          }
        }
      }

      AnimatedVisibility(
        visible = hudVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(
              Brush.verticalGradient(
                listOf(Color.Transparent, Color(0xD8060812), Color(0xF2060812))
              )
            )
            .padding(horizontal = 32.dp, vertical = 20.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                nowPlayingTitle.ifBlank { "Google TV Live Channels" },
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                "$statusMessage · CH+/CH- Switch · Back Hide HUD",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(
                onClick = onOpenSystemSetup,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.14f))
              ) { Text("Sources Setup", color = Color.White) }
              Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B5CFF))
              ) { Text("TV Home", color = Color.White) }
            }
          }

          if (sources.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("ALL TV SOURCES", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            LazyRow(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              contentPadding = PaddingValues(end = 16.dp)
            ) {
              itemsIndexed(sources) { idx, src ->
                val active = idx == selectedSourceIdx
                val focused = active && focusZone == 0
                Surface(
                  onClick = { onSelectSource(idx) },
                  shape = RoundedCornerShape(16.dp),
                  color = if (active) Color(0x558D7BFF) else Color.White.copy(alpha = 0.10f),
                  border = BorderStroke(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) Color.White else Color.White.copy(alpha = 0.22f)
                  )
                ) {
                  Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(src.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(src.typeLabel, color = Color.White.copy(alpha = 0.68f), fontSize = 11.sp)
                  }
                }
              }
            }
          }

          if (channels.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("LIVE CHANNELS", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            LazyRow(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              contentPadding = PaddingValues(end = 16.dp)
            ) {
              itemsIndexed(channels) { idx, ch ->
                val active = idx == selectedChannelIdx
                val focused = active && focusZone == 1
                Surface(
                  onClick = { onSelectChannel(idx) },
                  shape = RoundedCornerShape(14.dp),
                  color = if (active) Color(0x5522D3EE) else Color.White.copy(alpha = 0.08f),
                  border = BorderStroke(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) Color.White else Color.White.copy(alpha = 0.18f)
                  )
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    if (ch.number.isNotBlank()) {
                      Text(ch.number, color = Color(0xFF7DD3FC), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                      Spacer(Modifier.width(8.dp))
                    }
                    Text(ch.name, color = Color.White, fontSize = 13.sp, maxLines = 1)
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
