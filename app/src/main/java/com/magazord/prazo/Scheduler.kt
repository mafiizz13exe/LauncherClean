package com.magazord.prazo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object Scheduler {
    fun proximoHorario(hora: Int, minuto: Int): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hora)
            set(Calendar.MINUTE, minuto)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis() + 1000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** Agenda o próximo disparo (um alarme por dia). Retorna o horário em ms. */
    fun agendar(ctx: Context): Long {
        val h = (Prefs.get(ctx, "hora", "9").toIntOrNull() ?: 9).coerceIn(0, 23)
        val m = (Prefs.get(ctx, "minuto", "20").toIntOrNull() ?: 20).coerceIn(0, 59)
        val t = proximoHorario(h, m)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val disparo = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, AlarmReceiver::class.java), flags)
        val mostrar = PendingIntent.getActivity(ctx, 2, Intent(ctx, ConfigActivity::class.java), flags)
        // setAlarmClock: exato, funciona em Doze e não exige permissão de alarme exato
        am.setAlarmClock(AlarmManager.AlarmClockInfo(t, mostrar), disparo)
        Prefs.put(ctx, "proximo", t.toString())
        return t
    }
}
