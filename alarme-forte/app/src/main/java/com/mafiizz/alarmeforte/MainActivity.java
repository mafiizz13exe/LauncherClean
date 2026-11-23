package com.mafiizz.alarmeforte;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    static final String PREFS = "alarmeforte";

    private TextView status;
    private MediaPlayer testPlayer;

    static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("enabled", true);
    }

    static void setEnabled(Context c, boolean v) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", v).apply();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Button button(String text, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(color);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));
        root.setBackgroundColor(0xFF101418);

        TextView title = new TextView(this);
        title.setText("⏰ Alarme Forte");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Quando o alarme tocar, o volume vai pro máximo sozinho. "
                + "Se não for desligado em 1 minuto, uma sirene aguda e vibração entram junto "
                + "(e ficam mais intensas aos 2 minutos).");
        desc.setTextColor(0xFFB8C0CC);
        desc.setTextSize(15);
        desc.setPadding(0, dp(12), 0, dp(12));
        root.addView(desc);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        root.addView(button("Ligar proteção", 0xFF2E7D32, v -> {
            setEnabled(this, true);
            startGuard();
            refresh();
        }));
        root.addView(button("Desligar proteção", 0xFF8E2A2A, v -> {
            setEnabled(this, false);
            stopService(new Intent(this, AlarmGuardService.class));
            refresh();
        }));
        root.addView(button("Liberar bateria (não deixar o Android matar o app)", 0xFF37474F, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }));
        root.addView(button("Testar (toca um alarme baixinho)", 0xFF1565C0, v -> startTest()));
        root.addView(button("Parar teste", 0xFF455A64, v -> stopTest()));

        TextView hint = new TextView(this);
        hint.setText("\nNo teste o app toca o som de alarme padrão com o volume lá embaixo. "
                + "Se estiver funcionando, o volume sobe sozinho. Depois de 1 min a sirene aguda começa.");
        hint.setTextColor(0xFF7F8896);
        hint.setTextSize(13);
        root.addView(hint);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (isEnabled(this)) startGuard();
        refresh();
    }

    @Override
    protected void onDestroy() {
        stopTest();
        super.onDestroy();
    }

    private void startGuard() {
        try {
            startForegroundService(new Intent(this, AlarmGuardService.class));
        } catch (Exception ignored) { }
    }

    private void refresh() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean bat = pm.isIgnoringBatteryOptimizations(getPackageName());
        status.setText("Proteção: " + (isEnabled(this) ? "LIGADA ✅" : "DESLIGADA ❌")
                + "\nBateria liberada: " + (bat ? "sim ✅" : "não ⚠️ (toque no botão abaixo)"));
    }

    private void startTest() {
        stopTest();
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0);
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            testPlayer = new MediaPlayer();
            testPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            testPlayer.setDataSource(this, uri);
            testPlayer.setLooping(true);
            testPlayer.prepare();
            testPlayer.start();
        } catch (Exception e) {
            status.setText("Erro no teste: " + e.getMessage());
        }
    }

    private void stopTest() {
        try {
            if (testPlayer != null) {
                testPlayer.stop();
                testPlayer.release();
            }
        } catch (Exception ignored) { }
        testPlayer = null;
    }
}
