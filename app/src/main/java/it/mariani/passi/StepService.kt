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

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIF_ID, n)
        }

        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        // Preferisce la versione "wake-up" del sensore: sveglia il telefono e consegna i passi puntuali
        val step = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true)
            ?: sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        step?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 30_000_000) }

        val baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        hasBarometer = baro != null
        baro?.let { sm.registerListener(this, it, 1_000_000, 30_000_000) }

        Scheduler.scheduleReport(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        sm.unregisterListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val a = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, e.values[0])
                alt = alt?.let { it + 0.15f * (a - it) } ?: a
            }
            Sensor.TYPE_STEP_COUNTER -> onCounter(e.values[0])
        }
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
            segSteps = 0; lastStepTime = 0; segStartAlt = null
            return
        }

        // Dopo una pausa lunga chiude la camminata precedente e riparte dalla quota attuale,
        // così le variazioni meteo della pressione durante le soste non diventano dislivello.
        if (lastStepTime > 0 && now - lastStepTime > GAP_MS) {
            finalizeSegment(altAtLastStep)
            segStartAlt = alt
        }
        if (segSteps == 0L && segStartAlt == null) segStartAlt = alt

        segSteps += delta
        lastStepTime = now
        altAtLastStep = alt
        if (segSteps >= SEGMENT_STEPS) finalizeSegment(alt)

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
        segStartAlt = endAlt
    }

    private fun buildNotification(): Notification {
        val st = Store.stats(this)
        val text = if (Store.inWindow())
            String.format(Locale.ITALY, "Oggi %,d passi · %.2f km", st.total, Calc.km(this, st))
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
