package com.sahayat.app.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sahayat.app.emergency.AlertWatchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restarts the band relay after the phone reboots or the app is updated and the caregiver alert listener. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (BandLinkService.shouldRun(context)) BandLinkService.start(context)
                if (AlertWatchService.shouldRun(context)) AlertWatchService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
