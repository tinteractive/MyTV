// SPDX-License-Identifier: GPL-3.0-only
package com.mytv.launcher.data

import org.json.JSONObject
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A tiny web page on the home network for the phone, only while its sheet is open: own photos to the TV, or
 * the settings file to the phone and back ([Mode]). Protection: one-time PIN (in the QR code), 5 wrong PINs
 * close it, size limits, one connection at a time, stops when the sheet closes. Photos are straightened (EXIF),
 * scaled to at most 2560 px and re-saved without camera metadata.
 */
class PhotoInbox(
    private val c: Context,
    private val texts: Texts,
    private val onReceived: () -> Unit,
    private val onLocked: () -> Unit = {},
    private val mode: Mode = Mode.PHOTOS,
    /** Settings put back from the phone (SETTINGS and RESTORE_SETTINGS); [onReceived] is then the file sent to the phone. */
    private val onRestored: () -> Unit = onReceived,
) {
    /** SETTINGS: one page for both, Save (to the phone) and Restore (from it). */
    enum class Mode { PHOTOS, RESTORE_SETTINGS, SETTINGS }
    class Texts(val title: String, val choose: String, val send: String, val done: String, val wrongPin: String, val pinLabel: String,
                val badFile: String = "", val save: String = "", val saved: String = "", val notAdded: String = "",
                /** Why a photo was not added, shown on the phone: keys large, many, small, image, cut. */
                val reasons: Map<String, String> = emptyMap())

    val pin = (1000 + java.security.SecureRandom().nextInt(9000)).toString()
    @Volatile private var stopped = false
    @Volatile private var client: Socket? = null
    private var received = 0
    @Volatile private var server: ServerSocket? = null
    private var wrongPins = 0

    /** Wrong PIN: after 5 the inbox closes (4 digits must not be guessable by trying). */
    private fun wrongPin() { if (++wrongPins >= 5) { main(onLocked); stop() } }
    var port = 0; private set
    /** "address:port" this page is served on; requests naming another host are refused (a web page elsewhere cannot reach it by name). */
    private var host = ""

    fun start(): Boolean = runCatching {
        // Only on the TV's home-network address (not on every interface): the page is meant for a phone on that network.
        val here = java.net.InetAddress.getByName(address() ?: return@runCatching false)
        val s = runCatching { ServerSocket(PORT, 4, here) }.getOrElse { ServerSocket(0, 4, here) }
        server = s; port = s.localPort; host = here.hostAddress + ":" + s.localPort
        thread(name = "photo-inbox", isDaemon = true) {
            while (!s.isClosed) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                // One connection at a time: plenty for a phone sending photos one by one, and nothing to flood.
                client = sock
                runCatching { sock.use { handle(it) } }.onFailure { Log.w("MyTV", "inbox: $it") }
                client = null
            }
        }
        true
    }.getOrDefault(false)

    /** Closes the server and any upload in progress; no callbacks after this. */
    fun stop() { stopped = true; runCatching { server?.close() }; runCatching { client?.close() }; server = null }

    private fun main(f: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post { if (!stopped) f() }

    fun url(): String? = host.takeIf { it.isNotEmpty() }?.let { "http://$it/?pin=$pin" }

    private fun handle(sock: Socket) {
        // A slow or idle client may not block the inbox: 10 s per read, 30 s for the request line and headers.
        sock.soTimeout = 10_000
        val inp = BufferedInputStream(sock.getInputStream())
        val head = readHead(inp, System.currentTimeMillis() + 30_000) ?: return
        val lines = head.split("\r\n")
        val (method, target) = lines[0].split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').mapNotNull { p ->
            p.indexOf('=').takeIf { it > 0 }?.let { URLDecoder.decode(p.substring(0, it), "UTF-8") to URLDecoder.decode(p.substring(it + 1), "UTF-8") }
        }.toMap()
        val out = sock.getOutputStream()
        if (headers["host"] != host) return reply(out, 400, "text/plain", byteArrayOf())
        when {
            method == "GET" && path == "/" -> reply(out, 200, "text/html; charset=utf-8", page().toByteArray())
            method == "GET" && path == "/check" -> if (query["pin"] == pin) reply(out, 200, "text/plain", byteArrayOf()) else { reply(out, 403, "text/plain", byteArrayOf()); wrongPin() }
            // Settings: the file to the phone, or back from it.
            method == "GET" && path == "/" + Backup.FILE_NAME && mode == Mode.SETTINGS -> {
                if (query["pin"] != pin) { reply(out, 403, "text/plain", texts.wrongPin.toByteArray()); wrongPin(); return }
                reply(out, 200, "application/json; charset=utf-8", Backup.export(c).toByteArray(),
                    "Content-Disposition: attachment; filename=\"${Backup.FILE_NAME}\"\r\n")
                main(onReceived)
            }
            method == "PUT" && path == "/restore" && (mode == Mode.RESTORE_SETTINGS || mode == Mode.SETTINGS) -> {
                if (query["pin"] != pin) { reply(out, 403, "text/plain", texts.wrongPin.toByteArray()); wrongPin(); return }
                val len = headers["content-length"]?.toLongOrNull() ?: return reply(out, 411, "text/plain", byteArrayOf())
                if (len <= 0 || len > 1_000_000) return reply(out, 413, "text/plain", texts.badFile.toByteArray())
                val body = ByteArray(len.toInt()); var got = 0
                while (got < body.size) { val n = inp.read(body, got, body.size - got); if (n < 0) break; got += n }
                val ok = got == body.size && !stopped && Backup.restore(c, String(body, Charsets.UTF_8))
                reply(out, if (ok) 200 else 415, "text/plain", (if (ok) "ok" else texts.badFile).toByteArray())
                if (ok) main(onRestored)
            }
            method == "PUT" && path == "/upload" && mode == Mode.PHOTOS -> {
                if (query["pin"] != pin) { reply(out, 403, "text/plain", texts.wrongPin.toByteArray()); wrongPin(); return }
                val len = headers["content-length"]?.toLongOrNull() ?: return reply(out, 411, "text/plain", byteArrayOf())
                if (len <= 0 || len > MAX_BYTES) return refuse(out, "large")
                if (received >= MAX_PHOTOS) return refuse(out, "many")
                val tmp = File(c.cacheDir, "inbox.tmp")
                var left = len
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (left > 0) { val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; o.write(buf, 0, n); left -= n }
                }
                // A cut-off upload is not a photo.
                val why = if (left != 0L || stopped) "cut"
                    else runCatching { save(tmp) }.onFailure { Log.w("MyTV", "inbox save: $it") }.getOrDefault("image")
                tmp.delete()
                if (why == null) { received++; main(onReceived); reply(out, 200, "text/plain", "ok".toByteArray()) }
                else refuse(out, why)
            }
            else -> reply(out, 404, "text/plain", byteArrayOf())
        }
    }

    /** A photo not added: the reason (a key of [Texts.reasons]) goes to the phone and to MyTV's log. */
    private fun refuse(out: OutputStream, why: String) {
        Log.i("MyTV", "inbox: photo not added ($why)")
        reply(out, 415, "text/plain", why.toByteArray())
    }

    /** Checks it is a photo, straightens it, scales to ≤ 2560 px and stores a clean JPEG; null when saved, else why not. */
    private fun save(f: File): String? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return "image"
        if (maxOf(b.outWidth, b.outHeight) < 640 || minOf(b.outWidth, b.outHeight) < 360) return "small"
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return "image"
        val rot = when (runCatching { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
        }
        val scale = minOf(1f, MAX_SIDE / maxOf(bmp.width, bmp.height).toFloat())
        if (rot != 0f || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rot) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val dir = BackgroundRepository.ownDir(c)
        // Written under a temporary name first: a half-written file never shows up as a background.
        val name = "photo-${System.currentTimeMillis()}"
        val part = File(dir, "$name.part")
        val written = part.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        if (!written || !part.renameTo(File(dir, "$name.jpg"))) { part.delete(); return "image" }
        return null
    }

    private fun readHead(inp: BufferedInputStream, deadline: Long): String? {
        val sb = StringBuilder()
        while (sb.length < 16_384 && System.currentTimeMillis() < deadline) {
            val ch = inp.read(); if (ch < 0) return null
            sb.append(ch.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString().trimEnd()
        }
        return null
    }

    private fun reply(out: OutputStream, code: Int, type: String, body: ByteArray, extra: String = "") {
        out.write("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n${extra}Cache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body); out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    /** A text as a JavaScript string literal (quotes included), safe inside a script element. */
    private fun js(s: String) = JSONObject.quote(s).replace("<", "\\u003c")

    /**
     * The page for the phone, in MyTV's own look (dark, frosted cards). One file, nothing loaded from elsewhere. Texts
     * are the app's own, in MyTV's language; in HTML they are escaped, in the script they are JavaScript strings.
     */
    private fun page(): String {
        val photos = mode == Mode.PHOTOS
        val svg = "viewBox=\"0 0 24 24\" fill=\"none\" stroke-linecap=\"round\" stroke-linejoin=\"round\""
        val tv = "<svg width=\"16\" height=\"16\" $svg stroke=\"#14171b\" stroke-width=\"2.4\">" +
            "<rect x=\"2\" y=\"4\" width=\"20\" height=\"13\" rx=\"2.5\"/><path d=\"M8 21h8\"/></svg>"
        val up = "<svg width=\"30\" height=\"30\" $svg stroke=\"#fff\" stroke-width=\"1.8\"><path d=\"M12 16V4\"/>" +
            "<path d=\"M7 9l5-5 5 5\"/><path d=\"M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3\"/></svg>"
        // The phone shows the page in MyTV's language, right-to-left for Arabic, Hebrew and Persian.
        val loc = java.util.Locale.getDefault()
        val dir = if (android.text.TextUtils.getLayoutDirectionFromLocale(loc) == android.view.View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"
        return """<!doctype html><html lang="${loc.toLanguageTag()}" dir="$dir"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#1b2a3a"><title>MyTV</title><style>
:root{color-scheme:dark}*{box-sizing:border-box}
body{margin:0;min-height:100vh;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;color:#fff;display:flex;justify-content:center;
padding:26px 18px 44px;background:radial-gradient(130% 70% at 15% 0%,#3d5f80 0%,#1b2a3a 48%,#0e1319 100%) fixed #0e1319}
main{width:100%;max-width:460px}
.brand{display:flex;align-items:center;gap:10px;font-weight:700;font-size:15px;letter-spacing:.05em;opacity:.9;margin:2px 4px 20px}
.brand i{width:28px;height:28px;border-radius:9px;background:#fff;display:grid;place-items:center}
h1{font-size:30px;line-height:1.12;margin:0 4px 20px;font-weight:700;letter-spacing:-.01em}
.card{background:rgba(255,255,255,.09);border:1px solid rgba(255,255,255,.17);border-radius:24px;padding:18px;margin-bottom:14px;
-webkit-backdrop-filter:blur(18px);backdrop-filter:blur(18px)}
.label{font-size:12px;font-weight:600;letter-spacing:.1em;text-transform:uppercase;color:rgba(255,255,255,.6);margin:0 2px 10px}
#pin{width:100%;font:600 30px/1 system-ui,sans-serif;letter-spacing:.5em;text-align:center;padding:14px 0 14px .5em;border-radius:16px;
border:1px solid rgba(255,255,255,.2);background:rgba(0,0,0,.28);color:#fff;outline:0}
#pin:focus{border-color:#fff}
.pick{display:flex;flex-direction:column;align-items:center;gap:8px;border:1.5px dashed rgba(255,255,255,.38);border-radius:18px;
padding:24px 16px;text-align:center;font-size:18px;font-weight:600;cursor:pointer}
.pick span{font-size:14px;font-weight:400;color:rgba(255,255,255,.65);min-height:18px;word-break:break-word}
#thumbs{display:grid;grid-template-columns:repeat(4,1fr);gap:6px;margin-top:12px}
#thumbs:empty{display:none}
#thumbs img{width:100%;aspect-ratio:16/9;object-fit:cover;border-radius:8px;display:block}
button{width:100%;font:600 18px system-ui,sans-serif;padding:16px;border:0;border-radius:18px;background:#fff;color:#14171b;margin-top:14px}
button:active{transform:scale(.985)}button:disabled{opacity:.4}
.card>button:first-child{margin-top:0}
#bar{height:6px;border-radius:3px;background:rgba(255,255,255,.16);overflow:hidden;margin-top:14px;display:none}
#bar b{display:block;height:100%;width:0;background:#30d158;transition:width .25s}
#log{margin:16px 6px 0;font-size:16px;line-height:1.45;color:rgba(255,255,255,.86);min-height:24px;white-space:pre-line}
#log.ok b{display:block;color:#30d158;font-size:20px;margin-bottom:4px}
#log.bad{color:#ffd58a;font-weight:600}
#log p.bad{color:#ffd58a;font-weight:600;margin:10px 0 0}
</style></head><body><main>
<div class="brand"><i>$tv</i>MyTV</div>
<h1>${esc(texts.title)}</h1>
<div class="card"><div class="label">${esc(texts.pinLabel)}</div>
<input id="pin" type="text" inputmode="numeric" pattern="[0-9]*" maxlength="4" autocomplete="off"></div>
${if (mode == Mode.SETTINGS) "<div class=\"card\"><button id=\"save\">${esc(texts.save)}</button></div>" else ""}
<div class="card"><label class="pick">$up${esc(texts.choose)}<span id="picked"></span>
<input id="f" type="file" accept="${if (photos) "image/*\" multiple" else ".json,application/json\""} hidden></label>
<div id="thumbs"></div>
<button id="go" disabled>${esc(texts.send)}</button><div id="bar"><b></b></div></div>
<div id="log"></div>
</main><script>
var q=new URLSearchParams(location.search),E=function(i){return document.getElementById(i)};
var pinEl=E('pin'),f=E('f'),log=E('log'),go=E('go'),bar=E('bar'),picked=E('picked'),thumbs=E('thumbs');
var photos=${photos},WRONG=${js(texts.wrongPin)},DONE=${js(texts.done)},SAVED=${js(texts.saved)},NOT_ADDED=${js(texts.notAdded)},REASON=${JSONObject(texts.reasons).toString().replace("<", "\\u003c")};
pinEl.value=q.get('pin')||'';
function say(t,c){log.className=c||'';log.textContent=t}
function progress(p){bar.style.display=p<0?'none':'block';bar.firstChild.style.width=Math.max(p,0)+'%'}
async function pinOk(){var c=await fetch('/check?pin='+encodeURIComponent(pinEl.value)).catch(function(){return null});
 if(!c||!c.ok){say(WRONG,'bad');return false}return true}
f.onchange=function(){say('');thumbs.textContent='';go.disabled=!f.files.length;
 picked.textContent=!f.files.length?'':photos?String(f.files.length):f.files[0].name;
 if(photos)Array.prototype.slice.call(f.files,0,12).forEach(function(x){var im=new Image();im.src=URL.createObjectURL(x);
  im.onload=function(){URL.revokeObjectURL(im.src)};thumbs.appendChild(im)})};
var sv=E('save');
if(sv)sv.onclick=async function(){if(!await pinOk())return;
 location.href='/${Backup.FILE_NAME}?pin='+encodeURIComponent(pinEl.value);say(SAVED,'ok')};
go.onclick=async function(){
 if(!f.files.length||!await pinOk())return;
 go.disabled=true;var pin=encodeURIComponent(pinEl.value);
 if(!photos){
  var r=await fetch('/restore?pin='+pin,{method:'PUT',body:f.files[0]}).catch(function(){return null});
  if(r&&r.ok)say(DONE,'ok');else{say(r?await r.text():'',r?'bad':'');go.disabled=false}
  return}
 var n=f.files.length,ok=0,failed=[];progress(0);
 for(var i=0;i<n;i++){
  say((i+1)+' / '+n+' …');
  var u=await fetch('/upload?pin='+pin,{method:'PUT',body:f.files[i]}).catch(function(){return null});
  if(u&&u.status==403){progress(-1);say(WRONG,'bad');go.disabled=false;return}
  if(u&&u.ok)ok++;else{var why=u?await u.text():'cut';failed.push(f.files[i].name+': '+(REASON[why]||REASON.image))}
  progress((i+1)*100/n)}
 log.className='ok';log.textContent='';var b=document.createElement('b');b.textContent='✓ '+ok+' / '+n;log.appendChild(b);
 log.appendChild(document.createTextNode(DONE));
 // Which photos the TV did not take, so it is clear which ones to try again (or leave).
 if(failed.length){var p=document.createElement('p');p.className='bad';p.textContent=NOT_ADDED;log.appendChild(p);
  failed.forEach(function(t){var l=document.createElement('p');l.className='bad';l.textContent=t;log.appendChild(l)})}
 f.value='';picked.textContent='';thumbs.textContent='';setTimeout(function(){progress(-1)},800)};
</script></body></html>"""
    }

    companion object {
        const val PORT = 8765
        const val MAX_BYTES = 40L * 1024 * 1024
        const val MAX_SIDE = 2560
        const val MAX_PHOTOS = 50

        /** The TV's address on the home network. */
        fun address(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
