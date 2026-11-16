package org.linesapp.follow

import android.content.Context
import android.util.Base64
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Lets someone else see and control the screen.
 * Messages are small JSON strings; both links carry exactly the same messages.
 */
interface RemoteHost {
    /** A message arrived from a remote page. Called on a background thread. */
    fun onRemoteMessage(json: String)
    /** A remote page connected and needs the script and current state. Background thread. */
    fun onRemoteJoined()
    /** Connection status changed (for the share screen). Background thread. */
    fun onRemoteStatus()
}

// ---------------------------------------------------------------- same Wi-Fi

/** A tiny web server on the phone: the page itself, a live event stream, and a command endpoint. */
class LocalServer(private val ctx: Context, private val host: RemoteHost) {
    val port = 8765
    private var server: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<OutputStream>()
    private val sendPool = Executors.newSingleThreadExecutor()
    @Volatile var running = false
        private set
    val viewers: Int get() = clients.size

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val ss = ServerSocket(port)
                ss.reuseAddress = true
                server = ss
                while (running) {
                    val s = ss.accept()
                    Thread { handle(s) }.start()
                }
            } catch (e: Exception) {
                running = false
            }
            host.onRemoteStatus()
        }.start()
        // keep connections alive through Wi-Fi power saving
        Thread {
            while (running) {
                Thread.sleep(15000)
                sendRaw(": ping\n\n")
            }
        }.start()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        for (c in clients) try { c.close() } catch (_: Exception) {}
        clients.clear()
    }

    fun send(json: String) = sendRaw("data: $json\n\n")

    private fun sendRaw(text: String) {
        if (clients.isEmpty()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        sendPool.execute {
            for (c in clients) {
                try { c.write(bytes); c.flush() } catch (e: Exception) {
                    clients.remove(c); host.onRemoteStatus()
                }
            }
        }
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 0
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return s.close()
            val parts = requestLine.split(" ")
            if (parts.size < 2) return s.close()
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            var length = 0
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                val idx = h.indexOf(':')
                if (idx > 0 && h.substring(0, idx).trim().equals("content-length", true)) length = h.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
            val out = s.getOutputStream()
            when {
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    val page = ctx.assets.open("web/remote.html").use { it.readBytes() }
                    respond(out, "200 OK", "text/html; charset=utf-8", page); s.close()
                }
                method == "GET" && path == "/icon.svg" -> {
                    respond(out, "200 OK", "image/svg+xml", ICON.toByteArray()); s.close()
                }
                method == "GET" && path == "/events" -> {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n" +
                        "Connection: keep-alive\r\nX-Accel-Buffering: no\r\n\r\n").toByteArray())
                    out.write(": hello\n\n".toByteArray()); out.flush()
                    clients.add(out)
                    host.onRemoteStatus()
                    host.onRemoteJoined()
                    // the socket stays open; it's closed when a write fails
                }
                method == "POST" && path == "/cmd" -> {
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
                    respond(out, "204 No Content", "text/plain", ByteArray(0)); s.close()
                    host.onRemoteMessage(String(body, 0, read, Charsets.UTF_8))
                }
                else -> { respond(out, "404 Not Found", "text/plain", "Not found".toByteArray()); s.close() }
            }
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun respond(out: OutputStream, status: String, type: String, body: ByteArray) {
        out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body); out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    companion object {
        /** The phone's address on Wi-Fi or its own hotspot. */
        fun localAddresses(): List<String> {
            val out = ArrayList<String>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val name = ni.name.lowercase()
                    if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy")) continue // mobile data
                    for (a in ni.inetAddresses) if (a is Inet4Address && a.isSiteLocalAddress) out.add(a.hostAddress ?: continue)
                }
            } catch (_: Exception) {}
            return out
        }
        private const val ICON = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 108 108'><rect width='108' height='108' fill='#14121a'/><rect x='24' y='47' width='44' height='8' fill='#f2c14e'/></svg>"
    }
}

// ---------------------------------------------------------------- anywhere online

/**
 * Online link through a free public relay (HiveMQ's public MQTT broker).
 * Everything is encrypted on the phone with a key that only exists in the link,
 * so the relay only ever sees scrambled data.
 */
class OnlineLink(private val host: RemoteHost) {
    var room: String = ""
        private set
    var keyB64Url: String = ""
        private set
    private var key: ByteArray = ByteArray(32)
    private var client: MqttAsyncClient? = null
    @Volatile var connected = false
        private set
    @Volatile var wanted = false
        private set
    private val rnd = SecureRandom()

    fun setCredentials(room: String, keyB64Url: String) {
        this.room = room
        this.keyB64Url = keyB64Url
        key = Base64.decode(keyB64Url, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun newCredentials() {
        val r = ByteArray(9); rnd.nextBytes(r)
        val k = ByteArray(32); rnd.nextBytes(k)
        setCredentials(r.joinToString("") { "%02x".format(it) },
            Base64.encodeToString(k, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
    }

    fun link(): String = "$PAGE#r=$room&k=$keyB64Url"

    fun start() {
        if (wanted) return
        wanted = true
        Thread {
            try {
                val c = MqttAsyncClient(BROKER, "lf-" + room.take(8) + "-" + System.currentTimeMillis().toString(36), MemoryPersistence())
                c.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        connected = true
                        try { c.subscribe("linesfollow/$room/down", 0) } catch (_: Exception) {}
                        host.onRemoteStatus()
                        host.onRemoteJoined()
                    }
                    override fun connectionLost(cause: Throwable?) { connected = false; host.onRemoteStatus() }
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val plain = decrypt(message?.payload ?: return) ?: return
                        host.onRemoteMessage(plain)
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                val o = MqttConnectOptions()
                o.isCleanSession = true
                o.isAutomaticReconnect = true
                o.keepAliveInterval = 30
                o.connectionTimeout = 20
                o.maxInflight = 50
                client = c
                c.connect(o, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {}
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        connected = false; host.onRemoteStatus()
                        // try again shortly while sharing is still on
                        Thread { Thread.sleep(5000); if (wanted && !connected) { stop(); start() } }.start()
                    }
                })
            } catch (e: Exception) {
                connected = false; host.onRemoteStatus()
            }
        }.start()
    }

    fun stop() {
        wanted = false
        connected = false
        val c = client
        client = null
        Thread {
            try { c?.disconnectForcibly(500, 500) } catch (_: Exception) {}
            try { c?.close() } catch (_: Exception) {}
        }.start()
    }

    fun send(json: String) {
        val c = client ?: return
        if (!connected) return
        try {
            val m = MqttMessage(encrypt(json))
            m.qos = 0
            c.publish("linesfollow/$room/up", m)
        } catch (_: Exception) {}
    }

    // AES-256-GCM: payload = base64( 12-byte IV + ciphertext + tag ), same as the web page's WebCrypto.
    private fun encrypt(text: String): ByteArray {
        val iv = ByteArray(12); rnd.nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = c.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encode(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(payload: ByteArray): String? = try {
        val raw = Base64.decode(payload, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    } catch (e: Exception) { null }

    companion object {
        const val BROKER = "ssl://broker.hivemq.com:8883"
        const val PAGE = "https://personal-project-maker.github.io/lines/remote.html"
    }
}
