package org.teslatempo.drive

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.teslatempo.MainActivity
import org.teslatempo.R

object DriveRuntime {
    internal val mutable = MutableStateFlow(DriveState())
    val state = mutable.asStateFlow()
    internal var requestDestination = false
    internal var requestOverlay = false
    fun syncDestination() { requestDestination = true }
}

data class CockpitPreferences(val size: Float = 32f, val offset: Int = 64, val opacity: Float = 0.7f, val showRange: Boolean = true) {
    fun save(context: Context) { context.getSharedPreferences("cockpit_ui", 0).edit()
        .putFloat("size",size).putInt("offset",offset).putFloat("opacity",opacity).putBoolean("range",showRange).apply() }
    companion object {
        fun load(context: Context): CockpitPreferences {
            val p = context.getSharedPreferences("cockpit_ui",0)
            return CockpitPreferences(p.getFloat("size",32f).coerceIn(24f,48f), p.getInt("offset",64).coerceIn(16,180),
                p.getFloat("opacity",0.7f).coerceIn(0.3f,0.75f),p.getBoolean("range",true))
        }
    }
}

/** Same Android view renders the preview and the small, non-touchable overlay window. */
class CockpitBlock(context: Context, private val font: Float) : LinearLayout(context) {
    private val main = TextView(context)
    private val secondary = TextView(context)
    init {
        orientation = VERTICAL
        gravity = Gravity.END
        setPadding(12,8,12,8)
        setBackgroundColor(Color.rgb(16,16,16))
        main.maxWidth = (resources.displayMetrics.widthPixels * 0.40f).toInt()
        secondary.maxWidth = main.maxWidth
        main.setTextColor(Color.WHITE); main.textSize = font; main.typeface = android.graphics.Typeface.DEFAULT_BOLD
        secondary.setTextColor(Color.WHITE); secondary.textSize = font * 0.58f
        addView(main); addView(secondary)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    fun render(text: CockpitText) {
        main.text = text.battery
        secondary.text = text.range
        secondary.visibility = if (text.range.isEmpty()) GONE else VISIBLE
    }
}

class DriveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionJob: Job? = null
    private var transport: TeslaBleTransport? = null
    private val overlay by lazy { CockpitOverlay(this) }
    private var wantsOverlay = false
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Cockpit Bluetooth", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSession(); return START_NOT_STICKY }
        if (intent?.action !in setOf(START, PAIR)) { stopSelf(); return START_NOT_STICKY }
        if (sessionJob?.isActive == true) return START_NOT_STICKY
        try {
            val stop = PendingIntent.getService(this, 2, Intent(this, DriveService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("TeslaTempo · cockpit BLE").setContentText("Lecture locale · toucher pour ouvrir")
                .setOngoing(true).setContentIntent(open).addAction(Notification.Action.Builder(null, "Arrêter le cockpit", stop).build()).build()
            startForeground(81, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: Exception) {
            DriveRuntime.mutable.value = DriveState(message = "Android refuse le service Bluetooth. Vérifiez les autorisations.")
            stopSelf(); return START_NOT_STICKY
        }
        val pairing = intent?.action == PAIR
        DriveRuntime.requestDestination = false; DriveRuntime.requestOverlay = false
        DriveRuntime.mutable.value = DriveState(running = true, message = "Recherche de la voiture à proximité…")
        sessionJob = scope.launch {
            try {
                val identity = DriveIdentity(this@DriveService)
                val (vin, key) = withContext(Dispatchers.IO) {
                    (identity.loadVin() ?: throw DriveFailure("Renseignez le VIN de votre voiture.")) to identity.keyPair()
                }
                val ble = TeslaBleTransport(this@DriveService); transport = ble
                ble.connect(vin)
                if (pairing) {
                    ble.send(TeslaSession.enrollment(DriveIdentity.publicBytes(key.public)))
                    DriveRuntime.mutable.value = DriveState(message = "Demande envoyée, ajout non confirmé : présentez la carte-clé sur le lecteur de la voiture et validez à l’écran, puis touchez Connecter en BLE.")
                    return@launch
                }
                val session = TeslaSession(vin, key, SystemClock::elapsedRealtime)
                ble.send(session.handshake())
                withContext(Dispatchers.Default) { session.acceptHandshake(ble.response(session, true)) }
                DriveRuntime.mutable.value = DriveState(running = true, connected = true, message = "Connecté en BLE · lecture seule")
                var lastCharge = -30_000L
                while (isActive) {
                    val wantsDestination = DriveRuntime.requestDestination
                    DriveRuntime.requestDestination = false
                    val now = SystemClock.elapsedRealtime()
                    if (wantsDestination) {
                        try {
                            ble.send(session.readRequest(false))
                            val drive = withContext(Dispatchers.Default) { session.readResponse(ble.response(session)) }.driveState
                            val destination = DriveDestination.from(
                                if (drive.hasActiveRouteDestination()) drive.activeRouteDestination else null,
                                if (drive.hasActiveRouteCoordinates()) drive.activeRouteCoordinates.latitude.toDouble() else null,
                                if (drive.hasActiveRouteCoordinates()) drive.activeRouteCoordinates.longitude.toDouble() else null,
                                SystemClock.elapsedRealtime())
                            DriveRuntime.mutable.value = DriveRuntime.mutable.value.copy(destination = destination,
                                message = if (destination == null) "Destination Tesla indisponible. Vous pouvez utiliser Waze directement." else "Destination Tesla récupérée · prête pour Waze")
                        } catch (e: DriveFailure) {
                            if (!e.recoverableData) throw e
                            DriveRuntime.mutable.value = DriveRuntime.mutable.value.copy(destination=null, message=e.userMessage)
                        }
                    }
                    if (now - lastCharge >= 30_000) {
                        try {
                        ble.send(session.readRequest(true))
                        val charge = withContext(Dispatchers.Default) { session.readResponse(ble.response(session)) }.chargeState
                        val range = if (charge.hasBatteryRange()) milesToKm(charge.batteryRange.toDouble()) else null
                        DriveRuntime.mutable.value = DriveRuntime.mutable.value.copy(
                            battery = if (charge.hasBatteryLevel()) charge.batteryLevel.takeIf { it in 0..100 } else null,
                            rangeKm = range, chargeAt = SystemClock.elapsedRealtime())
                        } catch (e: DriveFailure) {
                            if (!e.recoverableData) throw e
                            DriveRuntime.mutable.value = DriveRuntime.mutable.value.copy(battery=null, rangeKm=null, chargeAt=null, message=e.userMessage)
                        }
                        lastCharge = now
                    }
                    delay(2_000)
                }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) DriveRuntime.mutable.value = DriveState(message = "Délai Bluetooth dépassé. Vérifiez le VIN, la proximité et l’écran de la voiture, puis reconnectez-vous.")
                else throw e
            } catch (e: Exception) {
                DriveRuntime.mutable.value = DriveState(message = if (e is DriveFailure) e.userMessage else "Connexion BLE interrompue ou non prise en charge. Vérifiez Bluetooth et les autorisations.")
            } finally {
                transport?.close(); transport = null; removeOverlay()
                DriveRuntime.mutable.value = DriveRuntime.mutable.value.copy(running = false, connected = false, overlay = false,
                    battery = null, rangeKm = null, destination = null)
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        scope.launch {
            while (isActive && sessionJob?.isActive == true) {
                try {
                    if (DriveRuntime.requestOverlay) { DriveRuntime.requestOverlay = false; wantsOverlay = true }
                    if (wantsOverlay && DriveRuntime.state.value.connected) {
                        if (!Settings.canDrawOverlays(this@DriveService)) throw DriveFailure("Autorisation de superposition retirée. Session arrêtée.")
                        if (!overlay.visible) addOverlay()
                        val text = DriveRuntime.state.value.cockpit(SystemClock.elapsedRealtime(), CockpitPreferences.load(this@DriveService).showRange)
                        overlay.render(text)
                    }
                } catch (_: Exception) { stopSession("Impossible d’afficher le cockpit. Vérifiez la permission de superposition.") }
                delay(500)
            }
        }
        return START_NOT_STICKY
    }
    private fun addOverlay() {
        overlay.show(CockpitPreferences.load(this))
        DriveRuntime.mutable.value = DriveRuntime.mutable.value.copy(overlay = true)
    }
    private fun removeOverlay() { overlay.close(); wantsOverlay = false }
    private fun stopSession(message: String = "Session BLE arrêtée") {
        sessionJob?.cancel(); transport?.close(); removeOverlay()
        DriveRuntime.mutable.value = DriveState(message = message)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlay.visible) {
            removeOverlay(); wantsOverlay = true
        }
    }
    override fun onTaskRemoved(rootIntent: Intent?) { stopSession() }
    override fun onDestroy() {
        sessionJob?.cancel(); transport?.close(); transport = null; removeOverlay(); scope.cancel()
        DriveRuntime.mutable.value = DriveRuntime.mutable.value.copy(running=false, connected=false, overlay=false,
            battery=null, rangeKm=null, destination=null)
        super.onDestroy()
    }
    companion object {
        const val START = "org.teslatempo.drive.START"
        const val PAIR = "org.teslatempo.drive.PAIR"
        const val STOP = "org.teslatempo.drive.STOP"
        private const val CHANNEL = "teslatempo_drive"
    }
}
