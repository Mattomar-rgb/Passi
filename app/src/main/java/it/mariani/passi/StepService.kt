package it.mariani.passi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.util.Locale

/**
 * Servizio sempre attivo che ascolta il contapassi hardware e il barometro.
 * I passi vengono raggruppati in segmenti (circa 40 passi o fine di una camminata)
 * e ciascun segmento è classificato come salita, discesa o piano in base alla
 * pendenza media misurata col barometro.
 */
class StepService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL = "servizio"
        const val NOTIF_ID = 1
        private const val SEGMENT_STEPS = 40
        private const val GAP_MS = 120_000L
        private const val MIN_GRADE = 0.03f
        private const val MIN_DH = 1.0f

        /** Tiene sveglio il processore finché si cammina, più un margine dopo l'ultimo passo. */
        private const val WALK_AWAKE_MS = 60_000L
        /** Oltre questo intervallo l'ultima lettura del barometro è considerata vecchia. */
        private const val STALE_MS = 20_000L

        /** Vero mentre il servizio è in esecuzione (serve alla schermata per mostrarlo). */
        @Volatile var running = false
        /** Ultima quota barometrica filtrata e momento della lettura (per la diagnostica). */
        @Volatile var liveAlt: Float? = null
        @Volatile var lastBaroElapsed = 0L

        fun baroFresh() = lastBaroElapsed > 0 &&
            SystemClock.elapsedRealtime() - lastBaroElapsed < STALE_MS

        fun start(c: Context) {
            val i = Intent(c, StepService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
    }

    private lateinit var sm: SensorManager
    private var hasBarometer = false

    private var alt: Float? = null          // quota filtrata dal barometro
    private var segSteps = 0L
    private var segStartAlt: Float? = null
    private var altAtLastStep: Float? = null
    private var lastStepTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIF_ID, n)
        }
        running = true

        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        // Preferisce la versione "wake-up" del sensore: sveglia il telefono e consegna i passi puntuali
        val step = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true)
            ?: sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        step?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 30_000_000) }

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "passi:camminata")
            .apply { setReferenceCounted(false) }

        // Il barometro normale non sveglia il telefono: a schermo spento le sue letture
        // andrebbero perse. Per questo durante la camminata il processore resta sveglio
        // (vedi onCounter) e le letture arrivano regolarmente, una al secondo.
        val baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE, true)
            ?: sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        hasBarometer = baro != null
        baro?.let { sm.registerListener(this, it, 1_000_000, 0) }

        Scheduler.scheduleReport(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        sm.unregisterListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PRESSURE -> onPressure(e.values[0])
            Sensor.TYPE_STEP_COUNTER -> onCounter(e.values[0])
        }
    }

    private fun onPressure(hPa: Float) {
        val a = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hPa)
        // Dopo un'interruzione la quota vecchia non vale più (il meteo può averla spostata):
        // si riparte dalla lettura attuale invece di farla "scivolare" verso il nuovo valore.
        alt = if (!baroFresh() || alt == null) a else alt!! + 0.15f * (a - alt!!)
        lastBaroElapsed = SystemClock.elapsedRealtime()
        liveAlt = alt
        // Se una camminata è iniziata mentre il barometro era fermo, la quota di partenza
        // viene fissata alla prima lettura buona.
        if (segSteps > 0 && segStartAlt == null) segStartAlt = alt
    }

    private fun onCounter(v: Float) {
        val last = Store.lastCounter(this)
        Store.setLastCounter(this, v)
        if (last < 0f) return
        var delta = (v - last).toLong()
        if (delta < 0) delta = v.toLong()       // il telefono è stato riavviato
        if (delta <= 0 || delta > 30_000) return

        val now = System.currentTimeMillis()
        if (!Store.inWindow(now)) {
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
            segSteps = 0; lastStepTime = 0; segStartAlt = null
            Store.setPending(this, Store.dayKey(), 0)
            return
        }

        // Dopo una pausa lunga chiude la camminata precedente e riparte dalla quota attuale,
        // così le variazioni meteo della pressione durante le soste non diventano dislivello.
        // Si cammina: il processore resta sveglio per un minuto dopo questo blocco di passi,
        // così il barometro continua a misurare anche con lo schermo spento.
        if (hasBarometer) wakeLock?.acquire(WALK_AWAKE_MS)

        val fresh = baroFresh()
        if (lastStepTime > 0 && now - lastStepTime > GAP_MS) {
            finalizeSegment(altAtLastStep)
            segStartAlt = if (fresh) alt else null
        }
        if (segSteps == 0L && segStartAlt == null && fresh) segStartAlt = alt

        segSteps += delta
        lastStepTime = now
        altAtLastStep = if (fresh) alt else null
        if (segSteps >= SEGMENT_STEPS) finalizeSegment(if (fresh) alt else null)

        Store.setPending(this, Store.dayKey(), segSteps)
        updateNotification()
    }

    private fun finalizeSegment(endAlt: Float?) {
        if (segSteps == 0L) { segStartAlt = endAlt; return }
        val start = segStartAlt
        var kind = "flat"
        var dh = 0f
        if (hasBarometer && start != null && endAlt != null) {
            dh = endAlt - start
            val grade = dh / (segSteps * Store.strideM(this))
            if (grade > MIN_GRADE && dh >= MIN_DH) kind = "up"
            else if (grade < -MIN_GRADE && dh <= -MIN_DH) kind = "down"
        }
        Store.add(this, Store.dayKey(), kind, segSteps, if (kind == "flat") 0f else dh)
        segSteps = 0
        segStartAlt = if (baroFresh()) endAlt else null
        Store.setPending(this, Store.dayKey(), 0)
    }

    private fun buildNotification(): Notification {
        val st = Store.stats(this)
        val tot = st.total + Store.pending(this)
        val text = if (Store.inWindow())
            String.format(Locale.ITALY, "Oggi %,d passi · %.2f km", tot, tot * Store.strideM(this) / 1000f)
        else "In pausa fino alle 6"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Contapassi attivo")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }
}

fun createChannels(c: Context) {
    val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
        NotificationChannel(StepService.CHANNEL, "Servizio contapassi", NotificationManager.IMPORTANCE_MIN)
    )
    nm.createNotificationChannel(
        NotificationChannel(ReportReceiver.CHANNEL, "Riepilogo delle 22", NotificationManager.IMPORTANCE_DEFAULT)
    )
}
