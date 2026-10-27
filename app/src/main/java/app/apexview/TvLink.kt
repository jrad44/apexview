package app.apexview

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.coroutines.resume

/** Whether this is running on a TV (Google TV, Android TV), where there's a remote and no touch. */
object Tv {
    fun isTv(context: Context): Boolean {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}

/**
 * Sign-in from the phone. Typing an email and password with a TV remote is miserable, so the TV
 * advertises itself on the home network and waits; the phone app, already signed in, finds it
 * and sends its F1 TV token along with the code shown on the TV.
 *
 * The protocol is one line each way over TCP:
 *   phone → TV:  APEXVIEW-PAIR 1 <code> <token>
 *   TV → phone:  OK | BAD | ERR <reason>
 * Five wrong codes in a row and the TV picks a new one.
 */
private const val SERVICE_TYPE = "_apexview._tcp"
private const val PREFERRED_PORT = 47820
private const val MAGIC = "APEXVIEW-PAIR"
private const val MAX_LINE = 32 * 1024
private const val MAX_MISSES = 5

/** The TV's side: shows a code, listens, and signs in with the token the phone sends. */
class TvPairing(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onToken: (String) -> Unit,
) {
    /** The six digits shown on screen. */
    var code by mutableStateOf("")
        private set

    /** Where the phone can reach this TV by hand, if it doesn't find it on its own. */
    var address by mutableStateOf<String?>(null)
        private set

    var status by mutableStateOf<String?>(null)
        private set

    private var server: ServerSocket? = null
    private var job: Job? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var misses = 0
    private val random = SecureRandom()
    private val nsd get() = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun start() {
        if (job != null) return
        code = newCode()
        misses = 0
        val socket = try {
            ServerSocket(PREFERRED_PORT)
        } catch (_: IOException) {
            ServerSocket(0)
        }
        server = socket
        address = localAddress()?.let { "${it.hostAddress}:${socket.localPort}" }
        register(socket.localPort)
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    client.use { handle(it) }
                } catch (_: IOException) {
                    // A phone that went away mid-message; keep listening.
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { server?.close() }
        server = null
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    private suspend fun handle(client: Socket) {
        client.soTimeout = 8_000
        val line = readLine(client.getInputStream()) ?: return
        val parts = line.trim().split(" ", limit = 4)
        val out = client.getOutputStream()
        fun reply(text: String) {
            out.write("$text\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }
        if (parts.size < 4 || parts[0] != MAGIC || parts[1] != "1") {
            reply("ERR unknown message")
            return
        }
        val token = parts[3].trim()
        val matched = withContext(Dispatchers.Main) {
            if (parts[2] == code && token.isNotEmpty()) {
                status = "Signing in…"
                onToken(token)
                true
            } else {
                misses++
                if (misses >= MAX_MISSES) {
                    code = newCode()
                    misses = 0
                }
                status = "That code didn't match. Check the code on the TV and try again."
                false
            }
        }
        reply(if (matched) "OK" else "BAD")
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "Apexview on ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                scope.launch(Dispatchers.Main) {
                    status = "Your phone may not find this TV by itself. Enter the address below instead."
                }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { registration = null }
    }

    private fun newCode(): String = "%06d".format(random.nextInt(1_000_000))
}

/** The phone's side: finds TVs waiting to be signed in, and sends the token to one. */
class TvFinder(private val context: Context, private val scope: CoroutineScope) {
    class Found(val name: String, val info: NsdServiceInfo)

    val found = mutableStateListOf<Found>()
    var searching by mutableStateOf(false)
        private set

    private var listener: NsdManager.DiscoveryListener? = null
    private val nsd get() = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun start() {
        if (listener != null) return
        found.clear()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                scope.launch(Dispatchers.Main) { searching = true }
            }
            override fun onDiscoveryStopped(serviceType: String) {
                scope.launch(Dispatchers.Main) { searching = false }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                scope.launch(Dispatchers.Main) { searching = false }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                scope.launch(Dispatchers.Main) {
                    if (found.none { it.name == info.serviceName }) found.add(Found(info.serviceName, info))
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                scope.launch(Dispatchers.Main) { found.removeAll { it.name == info.serviceName } }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { listener = null }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        searching = false
    }

    /**
     * Sends [token] to [target], or to [manual] ("192.168.1.20:47820") when typed by hand.
     * Returns null when the TV accepted it, else what went wrong, in words.
     */
    suspend fun send(target: Found?, manual: String?, code: String, token: String): String? = withContext(Dispatchers.IO) {
        try {
            val where = when {
                !manual.isNullOrBlank() -> parseAddress(manual.trim()) ?: return@withContext "That address doesn't look right. It should look like 192.168.1.20:47820."
                target != null -> resolve(target.info) ?: return@withContext "Couldn't reach that TV. Check it's on the same Wi-Fi."
                else -> return@withContext "Pick your TV, or enter its address."
            }
            Socket().use { socket ->
                socket.connect(where, 5_000)
                socket.soTimeout = 15_000
                socket.getOutputStream().apply {
                    write("$MAGIC 1 ${code.trim()} $token\n".toByteArray(Charsets.UTF_8))
                    flush()
                }
                when (val answer = readLine(socket.getInputStream())?.trim()) {
                    "OK" -> null
                    "BAD" -> "The TV says that code is wrong. Check the code on the TV."
                    null -> "The TV didn't answer."
                    else -> "The TV said: ${answer.removePrefix("ERR ")}"
                }
            }
        } catch (e: IOException) {
            "Couldn't reach the TV: ${e.message}"
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): InetSocketAddress? = try {
        withTimeout(8_000) {
            suspendCancellableCoroutine { cont ->
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host
                        if (cont.isActive) cont.resume(if (host != null) InetSocketAddress(host, info.port) else null)
                    }
                })
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun parseAddress(text: String): InetSocketAddress? {
        val host = text.substringBeforeLast(':', text)
        val port = if (':' in text) text.substringAfterLast(':').toIntOrNull() else PREFERRED_PORT
        if (host.isBlank() || port == null || port !in 1..65535) return null
        return runCatching { InetSocketAddress(InetAddress.getByName(host), port) }.getOrNull()
    }
}

/** Reads one line (up to [MAX_LINE] bytes), or null if the other side closed first. */
private fun readLine(input: InputStream): String? {
    val bytes = ByteArrayOutputStream()
    while (bytes.size() < MAX_LINE) {
        val b = input.read()
        if (b < 0) return if (bytes.size() > 0) bytes.toString("UTF-8") else null
        if (b == '\n'.code) return bytes.toString("UTF-8")
        bytes.write(b)
    }
    return bytes.toString("UTF-8")
}

/** This device's address on the home network, for typing into the phone by hand. */
private fun localAddress(): InetAddress? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
}.getOrNull()
