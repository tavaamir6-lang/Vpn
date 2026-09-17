package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.db.AppDatabase
import com.example.data.model.PerAppMode
import com.example.data.model.ProtocolType
import com.example.data.model.RoutingMode
import com.example.data.model.ServerConfig
import com.example.data.repository.SettingsRepository
import com.example.data.repository.TrafficRepository
import go.Seq
import hev.htproxy.TProxyService
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class V2RayVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var xray: CoreController? = null
    private var currentServer: ServerConfig? = null
    private var sessionStartTime: Long = 0L
    private var totalUpload: Long = 0L
    private var totalDownload: Long = 0L

    private val coreCallback = object : CoreCallbackHandler {
        override fun startup(): Long {
            Log.i(TAG, "Xray core started")
            return 0L
        }

        override fun shutdown(): Long {
            Log.i(TAG, "Xray core stopped")
            return 0L
        }

        override fun onEmitStatus(code: Long, message: String): Long {
            Log.i(TAG, "Xray[$code]: $message")
            if (code != 0L) {
                VpnManager.setError(message)
            }
            return 0L
        }
    }

    companion object {
        private const val TAG = "LightSpeedVPN"
        const val ACTION_START = "com.example.vpn.START"
        const val ACTION_STOP = "com.example.vpn.STOP"
        const val CHANNEL_ID = "v2ray_vpn_channel"
        const val NOTIFICATION_ID = 1001
        private const val MTU = 1500
        private const val IPV4_ADDRESS = "172.19.0.1"
        private const val IPV6_ADDRESS = "fd00:1:2::1"
        private const val SOCKS_PORT = 10808

        fun start(context: Context, server: ServerConfig) {
            val intent = Intent(context, V2RayVpnService::class.java).apply {
                action = ACTION_START
                putExtra("server_id", server.id)
                putExtra("server_name", server.name)
                putExtra("server_address", server.address)
                putExtra("server_port", server.port)
                putExtra("server_protocol", server.protocol.name)
                putExtra("server_uuid", server.uuidOrPassword)
                putExtra("server_security", server.security)
                putExtra("server_network", server.networkType)
                putExtra("server_tls", server.tls)
                putExtra("server_sni", server.sni)
                putExtra("server_host", server.host)
                putExtra("server_path", server.path)
                putExtra("server_alpn", server.alpn)
                putExtra("server_flow", server.flow)
                putExtra("server_public_key", server.publicKey)
                putExtra("server_short_id", server.shortId)
                putExtra("server_alter_id", server.alterId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, V2RayVpnService::class.java).apply { action = ACTION_STOP })
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(filesDir.absolutePath, UUID.randomUUID().toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Xray native environment", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val server = ServerConfig(
                    id = intent.getLongExtra("server_id", 0L),
                    name = intent.getStringExtra("server_name") ?: "V2Ray Server",
                    protocol = runCatching { ProtocolType.valueOf(intent.getStringExtra("server_protocol") ?: "VMESS") }.getOrDefault(ProtocolType.VMESS),
                    address = intent.getStringExtra("server_address") ?: "127.0.0.1",
                    port = intent.getIntExtra("server_port", 443),
                    uuidOrPassword = intent.getStringExtra("server_uuid") ?: "",
                    security = intent.getStringExtra("server_security") ?: "",
                    networkType = intent.getStringExtra("server_network") ?: "tcp",
                    tls = intent.getStringExtra("server_tls") ?: "none",
                    sni = intent.getStringExtra("server_sni") ?: "",
                    host = intent.getStringExtra("server_host") ?: "",
                    path = intent.getStringExtra("server_path") ?: "/",
                    alpn = intent.getStringExtra("server_alpn") ?: "",
                    flow = intent.getStringExtra("server_flow") ?: "",
                    publicKey = intent.getStringExtra("server_public_key") ?: "",
                    shortId = intent.getStringExtra("server_short_id") ?: "",
                    alterId = intent.getIntExtra("server_alter_id", 0)
                )
                startVpn(server)
            }
            ACTION_STOP -> stopVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn(server: ServerConfig) {
        if (serviceJob?.isActive == true) return
        currentServer = server
        VpnManager.setConnecting(server)
        startForeground(NOTIFICATION_ID, buildNotification(server.name, "Connecting…"))

        serviceJob = serviceScope.launch {
            try {
                val settings = SettingsRepository(applicationContext).settings.value
                val builder = Builder()
                    .setSession(server.name)
                    .setMtu(MTU)
                    .addAddress(IPV4_ADDRESS, 30)
                    .addAddress(IPV6_ADDRESS, 126)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer(settings.dnsServer.ifBlank { "1.1.1.1" })

                if (settings.mode == RoutingMode.PER_APP) {
                    if (settings.perAppMode == PerAppMode.ALLOW_SELECTED) {
                        for (pkg in settings.selectedPackages) {
                            if (pkg != packageName) runCatching { builder.addAllowedApplication(pkg) }
                        }
                    } else {
                        runCatching { builder.addDisallowedApplication(packageName) }
                        for (pkg in settings.selectedPackages) runCatching { builder.addDisallowedApplication(pkg) }
                    }
                } else {
                    // The VPN app itself must stay outside the VPN. This prevents the Xray
                    // upstream connection from being routed back into our own TUN.
                    runCatching { builder.addDisallowedApplication(packageName) }
                }

                val pfd = builder.establish() ?: throw IllegalStateException("Failed to establish Android TUN interface")
                vpnInterface = pfd

                val config = com.example.data.parser.XrayConfigGenerator.generateConfig(server, settings, SOCKS_PORT)
                val controller = Libv2ray.newCoreController(coreCallback)
                xray = controller

                // Hev owns the Android TUN in this architecture. Xray only exposes the
                // local SOCKS/HTTP inbounds and makes the upstream proxy connection.
                // Passing the Android TUN fd to Xray as well would make two native components
                // compete for the same TUN. v2rayNG explicitly uses fd=0 when Hev tun2socks
                // is enabled for the same reason.
                controller.startLoop(config, 0)

                val tunnelConfig = buildTunnelConfig()
                val configFile = File(cacheDir, "hev-tunnel.yml")
                configFile.writeText(tunnelConfig)
                if (!TProxyService.TProxyStartService(configFile.absolutePath, pfd.fd)) {
                    throw IllegalStateException("hev-socks5-tunnel failed to start")
                }

                sessionStartTime = System.currentTimeMillis()
                totalUpload = 0L
                totalDownload = 0L
                VpnManager.setConnected(server, sessionStartTime)
                getSystemService(Context.NOTIFICATION_SERVICE).let { it as NotificationManager }.notify(
                    NOTIFICATION_ID, buildNotification(server.name, "Connected • Protected")
                )
                runTrafficMonitor()
            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed", e)
                VpnManager.setError(e.localizedMessage ?: "VPN connection error")
                cleanupTunnel()
            }
        }
    }

    private fun buildTunnelConfig(): String = """
        tunnel:
          name: tun0
          mtu: $MTU
          multi-queue: false
          ipv4: $IPV4_ADDRESS
          ipv6: '$IPV6_ADDRESS'
          icmp: 'reply'
        socks5:
          address: 127.0.0.1
          port: $SOCKS_PORT
          udp: 'udp'
        misc:
          log-level: warn
    """.trimIndent()

    private suspend fun runTrafficMonitor() {
        while (serviceScope.isActive && vpnInterface != null && TProxyService.TProxyIsRunning()) {
            delay(1000)
            val stats = runCatching { TProxyService.TProxyGetStats() }.getOrNull()
            if (stats != null && stats.size >= 4) {
                val up = stats[1].coerceAtLeast(0L)
                val down = stats[3].coerceAtLeast(0L)
                totalUpload = up
                totalDownload = down
                val duration = (System.currentTimeMillis() - sessionStartTime) / 1000
                VpnManager.updateTraffic(duration, up, down, totalUpload, totalDownload)
            }
        }
    }

    private fun cleanupTunnel() {
        try { TProxyService.TProxyStopService() } catch (_: Exception) { }
        try { xray?.stopLoop() } catch (_: Exception) { }
        xray = null
        try { vpnInterface?.close() } catch (_: Exception) { }
        vpnInterface = null
    }

    private fun stopVpn() {
        VpnManager.setStopping()
        serviceJob?.cancel()
        serviceJob = null
        val duration = if (sessionStartTime > 0) (System.currentTimeMillis() - sessionStartTime) / 1000 else 0L
        if (duration > 0 || totalDownload > 0 || totalUpload > 0) {
            val server = currentServer
            val db = AppDatabase.getDatabase(applicationContext)
            val trafficRepo = TrafficRepository(db.trafficStatsDao())
            serviceScope.launch {
                trafficRepo.recordSessionTraffic(server?.id, server?.name ?: "Direct Server", totalUpload, totalDownload, duration)
            }
        }
        cleanupTunnel()
        VpnManager.setDisconnected()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows current VPN connection status and traffic"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            this, 1, Intent(this, V2RayVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .build()
    }
}
