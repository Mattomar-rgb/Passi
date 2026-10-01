package it.mariani.passi

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Conteggi di una giornata (solo fascia 6-22). */
data class DayStats(
    val up: Long,
    val down: Long,
    val flat: Long,
    val ascentM: Float,
    val descentM: Float
) {
    val total: Long get() = up + down + flat
}

object Store {
    const val START_HOUR = 6
    const val END_HOUR = 22

    private fun p(c: Context): SharedPreferences =
        c.getSharedPreferences("passi", Context.MODE_PRIVATE)

    fun dayKey(t: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ITALY).format(Date(t))

    fun inWindow(t: Long = System.currentTimeMillis()): Boolean {
        val h = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)
        return h in START_HOUR until END_HOUR
    }

    // --- profilo ---
    fun heightCm(c: Context) = p(c).getFloat("height", 175f)
    fun weightKg(c: Context) = p(c).getFloat("weight", 80f)
    fun strideCmManual(c: Context) = p(c).getFloat("stride", 0f)

    /** Lunghezza del passo in metri: manuale se impostata, altrimenti stimata dall'altezza. */
    fun strideM(c: Context): Float {
        val manual = strideCmManual(c)
        return if (manual > 0f) manual / 100f else heightCm(c) * 0.415f / 100f
    }

    fun saveProfile(c: Context, height: Float, weight: Float, stride: Float) {
        p(c).edit().putFloat("height", height).putFloat("weight", weight)
            .putFloat("stride", stride).apply()
    }

    // --- contatore hardware ---
    fun lastCounter(c: Context) = p(c).getFloat("lastCounter", -1f)
    fun setLastCounter(c: Context, v: Float) = p(c).edit().putFloat("lastCounter", v).apply()

    // --- passi gia' contati ma non ancora classificati (blocco in corso) ---
    fun pending(c: Context, day: String = dayKey()) = p(c).getLong("${day}_pending", 0)
    fun setPending(c: Context, day: String, v: Long) =
        p(c).edit().putLong("${day}_pending", v).apply()

    // --- statistiche giornaliere ---
    fun stats(c: Context, day: String = dayKey()): DayStats {
        val s = p(c)
        return DayStats(
            s.getLong("${day}_up", 0),
            s.getLong("${day}_down", 0),
            s.getLong("${day}_flat", 0),
            s.getFloat("${day}_asc", 0f),
            s.getFloat("${day}_desc", 0f)
        )
    }

    /** kind: "up", "down" o "flat"; dh in metri (positivo in salita, negativo in discesa). */
    fun add(c: Context, day: String, kind: String, steps: Long, dh: Float) {
        val s = p(c)
        val e = s.edit().putLong("${day}_$kind", s.getLong("${day}_$kind", 0) + steps)
        if (kind == "up") e.putFloat("${day}_asc", s.getFloat("${day}_asc", 0f) + dh)
        if (kind == "down") e.putFloat("${day}_desc", s.getFloat("${day}_desc", 0f) - dh)
        e.apply()
    }
}

object Calc {
    fun km(c: Context, st: DayStats) = st.total * Store.strideM(c) / 1000f

    /**
     * Calorie spese camminando, esclusa la spesa a riposo.
     * Piano circa 0,5 kcal per kg per km; salita aggiunge il lavoro contro gravità
     * (rendimento muscolare circa 25%); la discesa fa risparmiare una piccola quota.
     */
    fun kcal(c: Context, st: DayStats): Float {
        val w = Store.weightKg(c)
        val base = 0.5f * w * km(c, st)
        val climb = w * st.ascentM * 0.0094f
        val descentSaving = w * st.descentM * 0.0094f * 0.25f
        return (base + climb - descentSaving).coerceAtLeast(0f)
    }

    fun summary(c: Context, st: DayStats): String {
        val km = km(c, st)
        return String.format(
            Locale.ITALY,
            "%,d passi · %.2f km · %.0f kcal\nSalita %,d passi (+%.0f m) · Discesa %,d passi (−%.0f m) · Piano %,d passi",
            st.total, km, kcal(c, st), st.up, st.ascentM, st.down, st.descentM, st.flat
        )
    }
}
