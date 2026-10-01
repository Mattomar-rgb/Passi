package it.mariani.passi

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var today: TextView
    private lateinit var status: TextView
    private lateinit var height: EditText
    private lateinit var weight: EditText
    private lateinit var stride: EditText

    // Aggiornamento automatico della schermata ogni 2 secondi mentre l'app è aperta
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannels(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun label(t: String, size: Float = 16f) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, pad / 2, 0, pad / 4)
        }.also { col.addView(it) }
        fun number(hint: String) = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }.also { col.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }
        }.also { col.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

        label("Oggi (6–22)", 22f)
        today = label("")
        status = label("", 13f)

        label("Profilo", 22f)
        label("Altezza (cm)")
        height = number("175")
        label("Peso (kg)")
        weight = number("80")
        label("Lunghezza del passo in cm (vuoto = stimata dall'altezza)")
        stride = number("")

        button("Salva e avvia") { save() }
        button("Escludi dal risparmio batteria") { askBatteryExemption() }
        button("Mostra ora il riepilogo") {
            sendBroadcast(Intent(this, ReportReceiver::class.java))
        }

        setContentView(ScrollView(this).apply { addView(col) })

        height.setText(fmt(Store.heightCm(this)))
        weight.setText(fmt(Store.weightKg(this)))
        Store.strideCmManual(this).takeIf { it > 0 }?.let { stride.setText(fmt(it)) }

        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun fmt(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString()

    private fun refresh() {
        val st = Store.stats(this)
        val pending = if (Store.inWindow()) Store.pending(this) else 0L
        var txt = Calc.summary(this, st)
        if (pending > 0) txt += String.format(
            Locale.ITALY, "\n+ %,d passi appena fatti, in attesa di classificazione", pending
        )
        today.text = txt

        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val hasStep = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        val hasBaro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        val notifOk = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .areNotificationsEnabled()
        val counting = when {
            !StepService.running -> "FERMO, premi Salva e avvia"
            Store.inWindow() -> "attivo"
            else -> "attivo, in pausa fino alle 6"
        }
        val baroLine = when {
            !hasBaro -> "non disponibile"
            StepService.liveAlt == null || StepService.lastBaroElapsed == 0L -> "nessuna lettura ancora"
            else -> {
                val age = (SystemClock.elapsedRealtime() - StepService.lastBaroElapsed) / 1000
                String.format(Locale.ITALY, "%.1f m (letta %s)\nPressione grezza: %.2f hPa\nSensore: %s",
                    StepService.liveAlt,
                    if (age < 60) "$age s fa" else "${age / 60} min fa",
                    StepService.rawHpa, StepService.baroName)
            }
        }
        status.text = String.format(
            Locale.ITALY,
            "Conteggio: %s\nQuota dal barometro: %s\nContapassi hardware: %s\nBarometro: %s\nRisparmio batteria: %s\nNotifiche: %s\nPasso usato: %.0f cm",
            counting,
            baroLine,
            if (hasStep) "presente" else "ASSENTE, l'app non può funzionare",
            if (hasBaro) "presente" else "assente, salita e discesa non distinguibili",
            if (exempt) "escluso" else "ATTIVO, conviene escludere l'app",
            if (notifOk) "consentite" else "BLOCCATE, niente riepilogo delle 22",
            Store.strideM(this) * 100
        ) + if (StepService.log.isEmpty()) "\n\nUltimi blocchi: nessuno ancora"
            else "\n\nUltimi blocchi:\n" + StepService.log.joinToString("\n")
    }

    private fun save() {
        val h = height.text.toString().replace(',', '.').toFloatOrNull() ?: 175f
        val w = weight.text.toString().replace(',', '.').toFloatOrNull() ?: 80f
        val s = stride.text.toString().replace(',', '.').toFloatOrNull() ?: 0f
        Store.saveProfile(this, h, w, s)
        val started = startIfAllowed()
        // Il servizio impiega un attimo a partire: aggiorno subito e di nuovo dopo un secondo
        refresh()
        handler.postDelayed({ refresh() }, 1_000)
        if (started) Toast.makeText(this, "Salvato, conteggio attivo", Toast.LENGTH_SHORT).show()
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isEmpty()) startIfAllowed() else requestPermissions(needed.toTypedArray(), 10)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        startIfAllowed()
    }

    /** Avvia il servizio se il permesso c'è; restituisce true se è partito. */
    private fun startIfAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Serve il permesso Attività fisica per contare i passi", Toast.LENGTH_LONG).show()
            return false
        }
        StepService.start(this)
        Scheduler.scheduleReport(this)
        return true
    }

    private fun askBatteryExemption() {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try { startActivity(i) } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
