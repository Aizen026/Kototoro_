package org.skepsun.kototoro.video.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections

/**
 * Discovers DLNA devices on the local network via SSDP M-SEARCH.
 */
object SsdpDiscovery {

    private const val TAG = "SsdpDiscovery"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val SEARCH_TARGET = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val TIMEOUT_MS = 5000

    private val M_SEARCH = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 3\r\n")
        append("ST: $SEARCH_TARGET\r\n")
        append("\r\n")
    }

    /**
     * Discovers DLNA MediaRenderer devices. Returns a list of [DlnaDevice].
     * Requires [context] to acquire a WiFi MulticastLock (Android filters multicast by default).
     */
    suspend fun discover(context: Context, client: OkHttpClient): List<DlnaDevice> = withContext(Dispatchers.IO) {
        // Android requires a MulticastLock to receive multicast / broadcast UDP packets
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("SsdpDiscovery")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        val locations = mutableSetOf<String>()
        var socket: MulticastSocket? = null
        try {
            val group = InetAddress.getByName(SSDP_ADDRESS)

            // Find the WiFi network interface for proper multicast binding
            val wifiInterface = findWifiInterface(context)
            Log.d(TAG, "WiFi interface: ${wifiInterface?.name ?: "default"}")

            // Use MulticastSocket on ephemeral port bound to WiFi interface
            socket = MulticastSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = TIMEOUT_MS

            // Set the outgoing interface so packets go over WiFi, not cellular
            if (wifiInterface != null) {
                socket.networkInterface = wifiInterface
            }

            // Join the multicast group (some devices respond to the group address)
            if (wifiInterface != null) {
                socket.joinGroup(InetSocketAddress(group, SSDP_PORT), wifiInterface)
            } else {
                socket.joinGroup(group)
            }

            val sendData = M_SEARCH.toByteArray(Charsets.UTF_8)
            val sendPacket = DatagramPacket(sendData, sendData.size, group, SSDP_PORT)

            // Send M-SEARCH multiple times for reliability
            socket.send(sendPacket)
            Thread.sleep(200)
            socket.send(sendPacket)
            Thread.sleep(200)
            socket.send(sendPacket)

            Log.d(TAG, "Sent SSDP M-SEARCH on port ${socket.localPort}")

            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                try {
                    val receivePacket = DatagramPacket(buf, buf.size)
                    socket.receive(receivePacket)
                    val response = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                    Log.d(TAG, "SSDP response from ${receivePacket.address}: ${response.take(300)}")
                    parseLocationHeader(response)?.let { locations.add(it) }
                } catch (_: SocketTimeoutException) {
                    break
                }
            }

            // Leave group
            if (wifiInterface != null) {
                runCatching { socket.leaveGroup(InetSocketAddress(group, SSDP_PORT), wifiInterface) }
            } else {
                runCatching { socket.leaveGroup(group) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP discovery error", e)
        } finally {
            runCatching { socket?.close() }
            runCatching { multicastLock?.release() }
        }

        Log.d(TAG, "SSDP found ${locations.size} locations: $locations")

        locations.map { location ->
            async {
                fetchDeviceDescription(client, location)
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Finds the WiFi network interface by checking the device's WiFi IP.
     */
    private fun findWifiInterface(context: Context): NetworkInterface? {
        val wifiIp = NetworkUtils.getWifiIpAddress(context) ?: return null
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in Collections.list(iface.inetAddresses)) {
                    if (addr is Inet4Address && addr.hostAddress == wifiIp) {
                        return iface
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to find WiFi interface", e)
        }
        return null
    }

    private fun parseLocationHeader(response: String): String? {
        for (line in response.lines()) {
            if (line.startsWith("LOCATION:", ignoreCase = true)) {
                return line.substringAfter(":").trim()
            }
        }
        return null
    }

    private fun fetchDeviceDescription(client: OkHttpClient, location: String): DlnaDevice? {
        return try {
            val request = Request.Builder().url(location).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val xml = response.body.string()
            response.close()
            parseDeviceXml(xml, location)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch device description: $location", e)
            null
        }
    }

    /**
     * Parses device description XML to extract name, type, and AVTransport controlURL.
     */
    private fun parseDeviceXml(xml: String, location: String): DlnaDevice? {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))

            var friendlyName: String? = null
            var deviceType: String? = null
            var controlUrl: String? = null
            var inAvTransportService = false
            var currentTag: String? = null

            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        when (currentTag) {
                            "friendlyName" -> if (friendlyName == null) friendlyName = text
                            "deviceType" -> if (deviceType == null) deviceType = text
                            "serviceType" -> {
                                if (text.contains("AVTransport", ignoreCase = true)) {
                                    inAvTransportService = true
                                }
                            }
                            "controlURL" -> {
                                if (inAvTransportService && controlUrl == null) {
                                    controlUrl = text
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "service") {
                            inAvTransportService = false
                        }
                        currentTag = null
                    }
                }
            }

            if (friendlyName == null || controlUrl == null) return null

            // Resolve relative controlURL
            val baseUrl = location.substringBeforeLast("/")
            val fullControlUrl = if (controlUrl.startsWith("http")) {
                controlUrl
            } else if (controlUrl.startsWith("/")) {
                val urlParts = location.split("/")
                "${urlParts[0]}//${urlParts[2]}$controlUrl"
            } else {
                "$baseUrl/$controlUrl"
            }

            return DlnaDevice(
                name = friendlyName,
                location = location,
                controlUrl = fullControlUrl,
                deviceType = deviceType.orEmpty(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse device XML from $location", e)
            return null
        }
    }
}
