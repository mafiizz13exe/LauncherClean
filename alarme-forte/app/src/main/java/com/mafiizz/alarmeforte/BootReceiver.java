package com.mafiizz.alarmeforte;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Religa a proteção sozinha depois que o celular reinicia (ou o app é atualizado). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (MainActivity.isEnabled(ctx)) {
            try {
                ctx.startForegroundService(new Intent(ctx, AlarmGuardService.class));
            } catch (Exception ignored) {
            }
        }
    }
}
