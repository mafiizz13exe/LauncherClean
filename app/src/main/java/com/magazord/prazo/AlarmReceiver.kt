package com.magazord.prazo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent?) {
        Scheduler.agendar(ctx) // já agenda o dia seguinte

        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "prazo:alarme"
        )
        wl.acquire(60_000)

        val run = Intent(ctx, RunActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("auto", true)

        // 1) abre direto (funciona com a permissão "sobrepor outros apps")
        try { ctx.startActivity(run) } catch (_: Exception) {}

        // 2) reserva: notificação com tela cheia
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("prazo", "Execução diária", NotificationManager.IMPORTANCE_HIGH)
            )
            val pi = PendingIntent.getActivity(
                ctx, 3, run, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(ctx, "prazo")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Prazo Magazord")
                .setContentText("Ajustando prazos de expedição...")
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            nm.notify(1, n)
        } catch (_: Exception) {}
    }
}
