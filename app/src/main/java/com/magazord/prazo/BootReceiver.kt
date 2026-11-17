package com.magazord.prazo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (Prefs.get(ctx, "agendado", "0") == "1") Scheduler.agendar(ctx)
    }
}
