package it.mariani.passi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Riavvia il contapassi dopo l'accensione del telefono o un aggiornamento dell'app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                StepService.start(c)
                Scheduler.scheduleReport(c)
            }
        }
    }
}
