package com.example

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Lightweight local-router HTTP server for customizing the TV Home screen
 * from a phone browser on the same Wi-Fi network (zero external dependencies).
 */
class LanCustomizerServer(
  private val port: Int = 8787,
  private val getStateJson: () -> String,
  private val onApplyPayload: (JSONObject) -> Unit
) {
  private val running = AtomicBoolean(false)
  private var serverSocket: ServerSocket? = null
  private val executor = Executors.newCachedThreadPool()

  fun start() {
    if (!running.compareAndSet(false, true)) return
    Thread({
      try {
        val ss = ServerSocket(port)
        serverSocket = ss
        while (running.get() && !ss.isClosed) {
          try {
            val client = ss.accept()
            executor.execute { handleClient(client) }
          } catch (_: Exception) {
            if (!running.get()) break
          }
        }
      } catch (_: Exception) {
        running.set(false)
      }
    }, "tv-lan-server").apply {
      isDaemon = true
      start()
    }
  }

  fun stop() {
    running.set(false)
    try {
      serverSocket?.close()
    } catch (_: Exception) {}
    serverSocket = null
  }

  fun getLanUrl(): String {
    val ip = getLocalIpv4Address() ?: return ""
    return "http://$ip:$port"
  }

  private fun getLocalIpv4Address(): String? {
    return try {
      val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
      var fallback: String? = null
      while (interfaces.hasMoreElements()) {
        val nif = interfaces.nextElement()
        if (!nif.isUp || nif.isLoopback) continue
        val addrs = nif.inetAddresses
        while (addrs.hasMoreElements()) {
          val addr = addrs.nextElement()
          if (addr is Inet4Address && !addr.isLoopbackAddress) {
            val host = addr.hostAddress ?: continue
            if (addr.isSiteLocalAddress) return host
            if (fallback == null) fallback = host
          }
        }
      }
      fallback
    } catch (_: Exception) {
      null
    }
  }

  private fun handleClient(socket: Socket) {
    socket.use { s ->
      try {
        s.soTimeout = 5000
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore("?")

        var contentLength = 0
        while (true) {
          val line = reader.readLine() ?: break
          if (line.isEmpty()) break
          if (line.lowercase().startsWith("content-length:")) {
            contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
          }
        }

        val body = if (contentLength > 0 && contentLength < 65536) {
          val buf = CharArray(contentLength)
          var read = 0
          while (read < contentLength) {
            val n = reader.read(buf, read, contentLength - read)
            if (n <= 0) break
            read += n
          }
          String(buf, 0, read)
        } else {
          ""
        }

        val out = s.getOutputStream()
        when {
          method == "GET" && path == "/" -> {
            writeResponse(out, 200, "text/html; charset=utf-8", PHONE_UI_HTML)
          }
          method == "GET" && path == "/api/state" -> {
            writeResponse(out, 200, "application/json; charset=utf-8", getStateJson())
          }
          method == "POST" && path == "/api/apply" -> {
            val json = if (body.isNotBlank()) JSONObject(body) else JSONObject()
            onApplyPayload(json)
            writeResponse(out, 200, "application/json; charset=utf-8", "{\"ok\":true}")
          }
          else -> {
            writeResponse(out, 404, "text/plain; charset=utf-8", "Not found")
          }
        }
      } catch (_: Exception) {}
    }
  }

  private fun writeResponse(out: OutputStream, status: Int, contentType: String, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    val statusText = if (status == 200) "OK" else "Not Found"
    val headers =
      "HTTP/1.1 $status $statusText\r\n" +
        "Content-Type: $contentType\r\n" +
        "Content-Length: ${bytes.size}\r\n" +
        "Cache-Control: no-store\r\n" +
        "Connection: close\r\n\r\n"
    out.write(headers.toByteArray(StandardCharsets.US_ASCII))
    out.write(bytes)
    out.flush()
  }

  companion object {
    private val PHONE_UI_HTML = """
      <!DOCTYPE html>
      <html lang="en">
      <head>
        <meta charset="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        <title>TV Home · Phone Customizer</title>
        <style>
          :root { --bg:#0a0a0c; --card:rgba(255,255,255,.06); --bd:rgba(255,255,255,.12); --acc:#7c83ff; --txt:#fff; --dim:rgba(255,255,255,.68); }
          * { box-sizing:border-box; margin:0; padding:0; }
          body { background:radial-gradient(circle at 10% 5%, rgba(124,131,255,.22), transparent 55%), var(--bg); color:var(--txt); font-family:system-ui,-apple-system,sans-serif; padding:18px; max-width:640px; margin:0 auto; }
          h1 { font-size:22px; font-weight:600; margin-bottom:4px; }
          .sub { color:var(--dim); font-size:13px; margin-bottom:16px; }
          .card { background:var(--card); border:1px solid var(--bd); border-radius:20px; padding:16px; margin-bottom:14px; backdrop-filter:blur(20px); }
          .lbl { font-size:11px; text-transform:uppercase; letter-spacing:.12em; color:var(--dim); margin-bottom:10px; font-weight:600; }
          .row { display:flex; flex-wrap:wrap; gap:8px; }
          button, input { font-family:inherit; font-size:14px; border-radius:999px; border:1px solid var(--bd); background:rgba(255,255,255,.07); color:#fff; padding:9px 14px; }
          button { cursor:pointer; }
          button.on { background:rgba(124,131,255,.28); border-color:var(--acc); }
          input { width:100%; border-radius:14px; padding:11px 14px; background:rgba(0,0,0,.35); margin-bottom:10px; }
          .dpad { display:grid; grid-template-columns:repeat(3,1fr); gap:8px; max-width:260px; margin:0 auto 12px; }
          .dpad .ok { background:rgba(124,131,255,.32); border-color:var(--acc); font-weight:700; }
          .app-item { display:flex; align-items:center; justify-content:space-between; gap:8px; padding:9px 0; border-bottom:1px solid rgba(255,255,255,.08); }
          .app-item:last-child { border-bottom:none; }
          .app-name { font-size:14px; font-weight:500; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
          .app-pkg { font-size:11px; color:var(--dim); font-family:monospace; }
          .actions { display:flex; gap:6px; flex-shrink:0; }
          pre { background:rgba(0,0,0,.45); border:1px solid var(--bd); border-radius:14px; padding:12px; font-size:12px; white-space:pre-wrap; word-break:break-all; margin-top:8px; }
          #toast { position:fixed; bottom:18px; left:50%; transform:translateX(-50%); background:#161824; border:1px solid var(--acc); padding:9px 16px; border-radius:999px; font-size:13px; display:none; }
        </style>
      </head>
      <body>
        <h1>TV Home Remote &amp; Customizer</h1>
        <p class="sub">Connected over your local Wi-Fi router · Instant TV Remote</p>

        <div class="card">
          <div class="lbl">TV Remote Control</div>
          <div class="dpad">
            <div></div><button onclick="sendAction({type:'remote',key:'up'})">&#9650;</button><div></div>
            <button onclick="sendAction({type:'remote',key:'left'})">&#9664;</button>
            <button class="ok" onclick="sendAction({type:'remote',key:'ok'})">OK</button>
            <button onclick="sendAction({type:'remote',key:'right'})">&#9654;</button>
            <div></div><button onclick="sendAction({type:'remote',key:'down'})">&#9660;</button><div></div>
          </div>
          <div class="row" style="justify-content:center;">
            <button onclick="sendAction({type:'remote',key:'back'})">Back</button>
            <button onclick="sendAction({type:'screen',screen:'home'})">Home</button>
            <button onclick="sendAction({type:'screen',screen:'inputs'})">Inputs</button>
            <button onclick="sendAction({type:'remote',key:'play'})">&#9654; YT Preview</button>
            <button onclick="sendAction({type:'remote',key:'fav'})">&#9733; Pin</button>
            <button onclick="sendAction({type:'remote',key:'hide'})">Hide</button>
            <button onclick="sendAction({type:'remote',key:'ambient'})">&#10024; Ambient</button>
          </div>
          <div class="lbl" style="margin-top:12px;">TV Input Source &amp; Android Home</div>
          <div class="row">
            <button onclick="sendAction({type:'input',source:'live'})">&#128250; Live TV Player</button>
            <button onclick="sendAction({type:'input',source:'satellite'})">&#128225; Satellite Dish</button>
            <button onclick="sendAction({type:'input',source:'dtv'})">DTV Line</button>
            <button onclick="sendAction({type:'input',source:'hdmi1'})">HDMI 1</button>
            <button onclick="sendAction({type:'input',source:'hdmi2'})">HDMI 2</button>
            <button onclick="sendAction({type:'input',source:'av'})">AV / Line</button>
            <button onclick="sendAction({type:'input',source:'home'})">Android Home</button>
          </div>
        </div>

        <div class="card">
          <div class="lbl">&#10024; Gemini Smart TV Assistant</div>
          <div class="row" style="margin-bottom:10px;">
            <input id="gemBox" type="text" placeholder="Ask Gemini for movies, 4K YouTube videos, games, ADB tips..." style="margin-bottom:0;flex:1" />
            <button onclick="sendAction({type:'gemini',prompt:val('gemBox')})">Ask Gemini</button>
          </div>
          <div class="row">
            <button onclick="sendAction({type:'gemini',prompt:'Recommend 5 great movies or shows to watch tonight'})">Movie Ideas</button>
            <button onclick="sendAction({type:'gemini',prompt:'Suggest 4K relaxing or scenic YouTube channels for TV'})">4K YouTube</button>
            <button onclick="sendAction({type:'defaultLauncher'})">Set Default Launcher</button>
          </div>
        </div>

        <div class="card">
          <div class="lbl">YouTube Preview &amp; Search</div>
          <div class="row" style="margin-bottom:10px;">
            <input id="ytBox" type="text" placeholder="YouTube URL or Video ID (e.g. jfKfPfyJRdk)" style="margin-bottom:0;flex:1" />
            <button onclick="sendAction({type:'ytPreview',video:val('ytBox')})">Preview on TV</button>
          </div>
          <div class="row" id="ytOpts" style="margin-bottom:12px;"></div>
          <input id="searchBox" type="text" placeholder="Type app name to search on TV..." />
          <div class="row">
            <button onclick="sendAction({type:'search',query:val('searchBox')})">Open Search on TV</button>
            <button onclick="sendAction({type:'screen',screen:'cleaner'})">Open Cleaner</button>
            <button onclick="sendAction({type:'screen',screen:'settings'})">Settings</button>
          </div>
        </div>

        <div class="card">
          <div class="lbl">Ambient Light Feature</div>
          <div class="row" id="ambOpts" style="margin-bottom:10px;"></div>
          <div class="lbl" style="margin-top:10px;">Theme</div>
          <div class="row" id="themes"></div>
          <div class="lbl" style="margin-top:14px;">Logo Effect Animation</div>
          <div class="row" id="logoFx"></div>
          <div class="lbl" style="margin-top:14px;">Clock, Grid, Weather &amp; Motion</div>
          <div class="row" id="otherPrefs"></div>
          <div class="row" style="margin-top:10px;">
            <input id="cityBox" type="text" placeholder="Optional weather city (leave blank for TV location)" style="margin-bottom:0;flex:1" />
            <button onclick="sendAction({type:'weatherCity',city:val('cityBox')})">Set City</button>
          </div>
        </div>

        <div class="card">
          <div class="lbl">Apps on TV (Open · Favorite · Hide)</div>
          <div id="appsList">Loading TV apps...</div>
        </div>

        <div class="card">
          <div class="lbl">ADB Bloatware Commands (For Remote ADB Shell)</div>
          <div class="row">
            <button onclick="copyCmds('disable')">Copy Disable</button>
            <button onclick="copyCmds('undo')">Copy Undo</button>
          </div>
          <pre id="adbBox">No bloatware toggled on TV yet.</pre>
        </div>

        <div id="toast"></div>

        <script>
          let state = { prefs:{}, favorites:[], hidden:[], bloatSel:[], apps:[] };
          const val = id => document.getElementById(id).value;
          function toast(m){ const t=document.getElementById('toast'); t.textContent=m; t.style.display='block'; setTimeout(()=>t.style.display='none',2200); }
          async function load() {
            const r = await fetch('/api/state');
            state = await r.json();
            render();
          }
          async function sendAction(payload) {
            await fetch('/api/apply', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(payload) });
            toast('Sent to TV');
            setTimeout(load, 250);
          }
          function setPref(k, v) {
            state.prefs[k] = v;
            sendAction({ type:'prefs', prefs: state.prefs });
          }
          function render() {
            const p = state.prefs || {};
            const themes = ['adaptive','crimson','indigo','emerald','rose','amber','cyan','ultra'];
            document.getElementById('themes').innerHTML = themes.map(function(t) {
              return '<button class="' + (p.theme===t?'on':'') + '" onclick="setPref(\'theme\',\'' + t + '\')">' + t + '</button>';
            }).join('');
            const yts = [['focus','YT On Focus'],['always','YT Always'],['off','YT Off']];
            document.getElementById('ytOpts').innerHTML = yts.map(function(y) {
              return '<button class="' + ((p.ytPreview||'focus')===y[0]?'on':'') + '" onclick="setPref(\'ytPreview\',\'' + y[0] + '\')">' + y[1] + '</button>';
            }).join('') + '<button onclick="sendAction({type:\'remote\',key:\'nextClip\'})">Next Clip</button>';
            const ambs = [['adaptive','Adaptive'],['aurora','Aurora'],['cinema','Cinema'],['ocean','Ocean'],['off','Off']];
            const ints = [['subtle','Subtle'],['vivid','Vivid'],['max','Max']];
            document.getElementById('ambOpts').innerHTML =
              ambs.map(function(a) { return '<button class="' + ((p.ambientMode||'adaptive')===a[0]?'on':'') + '" onclick="setPref(\'ambientMode\',\'' + a[0] + '\')">' + a[1] + '</button>'; }).join('') +
              ints.map(function(i) { return '<button class="' + ((p.ambientLevel||'vivid')===i[0]?'on':'') + '" onclick="setPref(\'ambientLevel\',\'' + i[0] + '\')">' + i[1] + '</button>'; }).join('');
            const fx = ['pulse','float','sweep','off'];
            document.getElementById('logoFx').innerHTML = fx.map(function(f) {
              return '<button class="' + (p.logoFx===f?'on':'') + '" onclick="setPref(\'logoFx\',\'' + f + '\')">' + f + '</button>';
            }).join('');
            document.getElementById('otherPrefs').innerHTML =
              '<button class="' + (p.clock==='12'?'on':'') + '" onclick="setPref(\'clock\',\'12\')">12h</button>' +
              '<button class="' + (p.clock==='24'?'on':'') + '" onclick="setPref(\'clock\',\'24\')">24h</button>' +
              '<button class="' + (p.tempUnit!=='F'?'on':'') + '" onclick="setPref(\'tempUnit\',\'C\')">°C</button>' +
              '<button class="' + (p.tempUnit==='F'?'on':'') + '" onclick="setPref(\'tempUnit\',\'F\')">°F</button>' +
              '<button class="' + (p.grid==='compact'?'on':'') + '" onclick="setPref(\'grid\',\'compact\')">Compact (5)</button>' +
              '<button class="' + (p.grid==='standard'?'on':'') + '" onclick="setPref(\'grid\',\'standard\')">Standard (4)</button>' +
              '<button class="' + (p.grid==='large'?'on':'') + '" onclick="setPref(\'grid\',\'large\')">Large (3)</button>' +
              '<button class="' + (p.orbs==='on'?'on':'') + '" onclick="setPref(\'orbs\',\'on\')">Glow On</button>' +
              '<button class="' + (p.orbs==='off'?'on':'') + '" onclick="setPref(\'orbs\',\'off\')">Glow Static</button>' +
              '<button class="' + (p.corners==='rounded'?'on':'') + '" onclick="setPref(\'corners\',\'rounded\')">Rounded</button>' +
              '<button class="' + (p.corners==='crisp'?'on':'') + '" onclick="setPref(\'corners\',\'crisp\')">Crisp</button>' +
              '<button class="' + (p.tvOnly!==false?'on':'') + '" onclick="setPref(\'tvOnly\',true)">TV Apps Only</button>' +
              '<button class="' + (p.tvOnly===false?'on':'') + '" onclick="setPref(\'tvOnly\',false)">All Apps</button>' +
              '<button class="' + (p.startupOnBoot!==false?'on':'') + '" onclick="setPref(\'startupOnBoot\',!(p.startupOnBoot!==false))">TV Boot Startup</button>';
            const apps = state.apps || [];
            function esc(s){ return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
            document.getElementById('appsList').innerHTML = apps.map(function(a) {
              const isFav = (state.favorites||[]).indexOf(a.package) !== -1;
              const isHid = (state.hidden||[]).indexOf(a.package) !== -1;
              const safePkg = String(a.package).replace(/['"\\]/g, '');
              return '<div class="app-item">' +
                '<div style="min-width:0"><div class="app-name">' + esc(a.label) + '</div><div class="app-pkg">' + esc(a.package) + '</div></div>' +
                '<div class="actions">' +
                  '<button onclick="sendAction({type:\'launch\',package:\'' + safePkg + '\'})">Open</button>' +
                  '<button class="' + (isFav?'on':'') + '" onclick="sendAction({type:\'toggleFav\',package:\'' + safePkg + '\'})">&#9733;</button>' +
                  '<button class="' + (isHid?'on':'') + '" onclick="sendAction({type:\'toggleHide\',package:\'' + safePkg + '\'})">' + (isHid?'Show':'Hide') + '</button>' +
                '</div></div>';
            }).join('') || 'No apps reported yet.';

            const bloat = state.bloatSel || [];
            document.getElementById('adbBox').textContent = bloat.length
              ? '# Disable\n' + bloat.map(x=>'pm disable-user --user 0 '+x).join('\n') + '\n\n# Undo\n' + bloat.map(x=>'pm enable '+x).join('\n')
              : 'No bloatware toggled on TV yet.';
          }
          function copyCmds(kind) {
            const bloat = state.bloatSel || [];
            if (!bloat.length) { toast('Nothing selected on TV'); return; }
            const txt = kind === 'undo'
              ? bloat.map(x=>'pm enable '+x).join('\n')
              : bloat.map(x=>'pm disable-user --user 0 '+x).join('\n');
            navigator.clipboard?.writeText(txt).then(()=>toast('Copied '+kind+' commands')).catch(()=>toast('Select & copy from box'));
          }
          load();
        </script>
      </body>
      </html>
    """.trimIndent()
  }
}
