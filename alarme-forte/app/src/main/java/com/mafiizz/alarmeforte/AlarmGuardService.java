package com.mafiizz.alarmeforte;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioTrack;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.List;

/**
 * Fica vigiando o áudio do celular. Quando qualquer app (relógio, timer...) começa a tocar
 * som com uso "ALARME":
 *   - ETAPA 0 (imediato): coloca o volume do alarme no MÁXIMO e mantém assim enquanto tocar.
 *   - ETAPA 1 (após 1 min sem desligar): liga uma sirene própria, aguda (square wave ~2-3 kHz,
 *     a faixa em que o ouvido é mais sensível), + vibração + ganho extra de áudio.
 *   - ETAPA 2 (após 2 min): sirene mais rápida e mais aguda.
 * Quando o alarme é desligado/soneca, tudo para.
 */
public class AlarmGuardService extends Service {

    static final String CHANNEL = "guard";
    static final long ESCALATE_1_MS = 60_000;   // 1 minuto
    static final long ESCALATE_2_MS = 120_000;  // 2 minutos
    static final long QUIET_MS = 3_000;         // alarme "sumiu" por 3 s = acabou
    static final long TICK_MS = 500;

    private AudioManager am;
    private final Handler h = new Handler(Looper.getMainLooper());
    private AudioManager.AudioPlaybackCallback callback;

    private boolean ringing = false;
    private long ringStart = 0;
    private long quietSince = 0;
    private long ignoreUntil = 0;
    private int stage = 0;

    private AudioTrack siren;
    private boolean sirenOn = false;
    private LoudnessEnhancer enhancer;
    private PowerManager.WakeLock wakeLock;
    private Vibrator vibrator;

    @Override
    public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        callback = new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                if (!ringing && SystemClock.uptimeMillis() >= ignoreUntil && externalAlarms(configs) > 0) {
                    startRinging();
                }
            }
        };
        am.registerAudioPlaybackCallback(callback, h);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Alarme Forte ativo", NotificationManager.IMPORTANCE_MIN));
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Alarme Forte ativo")
                .setContentText("Vigiando o alarme do celular")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(1, n);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { am.unregisterAudioPlaybackCallback(callback); } catch (Exception ignored) { }
        stopRinging();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- detecção

    /** Quantos players com uso ALARME existem, sem contar a nossa própria sirene. */
    private int externalAlarms(List<AudioPlaybackConfiguration> configs) {
        int c = 0;
        if (configs != null) {
            for (AudioPlaybackConfiguration p : configs) {
                try {
                    AudioAttributes a = p.getAudioAttributes();
                    if (a != null && a.getUsage() == AudioAttributes.USAGE_ALARM) c++;
                } catch (Exception ignored) { }
            }
        }
        if (sirenOn) c--;
        return Math.max(0, c);
    }

    // ---------------------------------------------------------------- ciclo do toque

    private void startRinging() {
        ringing = true;
        stage = 0;
        ringStart = SystemClock.uptimeMillis();
        quietSince = 0;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alarmeforte:ring");
            wakeLock.acquire(15 * 60 * 1000L);
        } catch (Exception ignored) { }
        setMaxVolume();
        h.postDelayed(tick, TICK_MS);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ringing) return;
            long now = SystemClock.uptimeMillis();
            setMaxVolume(); // se você apertar "volume -", ele volta pro máximo

            int ext = externalAlarms(am.getActivePlaybackConfigurations());
            if (ext > 0) {
                quietSince = 0;
            } else if (quietSince == 0) {
                quietSince = now;
            } else if (now - quietSince >= QUIET_MS) {
                stopRinging();
                return;
            }

            long elapsed = now - ringStart;
            if (stage < 1 && elapsed >= ESCALATE_1_MS) enterStage(1);
            if (stage < 2 && elapsed >= ESCALATE_2_MS) enterStage(2);

            h.postDelayed(this, TICK_MS);
        }
    };

    private void stopRinging() {
        ringing = false;
        h.removeCallbacks(tick);
        stopSiren();
        stopVibration();
        releaseEnhancer();
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        wakeLock = null;
        stage = 0;
        // ignora os avisos atrasados do sistema sobre a sirene que acabamos de parar
        ignoreUntil = SystemClock.uptimeMillis() + 3000;
    }

    private void setMaxVolume() {
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) != max) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
            }
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- escalada

    private void enterStage(int s) {
        stage = s;
        stopSiren();
        startSiren(s);
        startVibration();
        tryEnhancer();
    }

    /** Sirene própria: onda quadrada em tom agudo, alternando duas frequências, em loop. */
    private void startSiren(int s) {
        try {
            final int sr = 44100;
            final double f1 = (s == 1) ? 2200 : 2700;
            final double f2 = (s == 1) ? 3000 : 3900;
            final int seg = (int) (sr * ((s == 1) ? 0.22 : 0.11));
            short[] data = new short[seg * 2];
            final short amp = (short) (Short.MAX_VALUE * 0.98);
            for (int part = 0; part < 2; part++) {
                double f = (part == 0) ? f1 : f2;
                for (int i = 0; i < seg; i++) {
                    double phase = (i * f / sr) % 1.0;
                    data[part * seg + i] = (phase < 0.5) ? amp : (short) -amp;
                }
            }
            siren = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(data.length * 2)
                    .build();
            siren.write(data, 0, data.length);
            siren.setLoopPoints(0, data.length, -1);
            siren.setVolume(1.0f);
            sirenOn = true;
            siren.play();
        } catch (Exception e) {
            sirenOn = false;
        }
    }

    private void stopSiren() {
        sirenOn = false;
        try {
            if (siren != null) {
                siren.stop();
                siren.release();
            }
        } catch (Exception ignored) { }
        siren = null;
    }

    private void startVibration() {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 600, 200}, 0),
                        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            }
        } catch (Exception ignored) { }
    }

    private void stopVibration() {
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) { }
    }

    /** Ganho extra no áudio global. Em alguns aparelhos não faz efeito (a sirene é o plano B garantido). */
    private void tryEnhancer() {
        releaseEnhancer();
        try {
            enhancer = new LoudnessEnhancer(0);
            enhancer.setTargetGain(1500); // +15 dB
            enhancer.setEnabled(true);
        } catch (Throwable t) {
            enhancer = null;
        }
    }

    private void releaseEnhancer() {
        try { if (enhancer != null) { enhancer.setEnabled(false); enhancer.release(); } } catch (Throwable ignored) { }
        enhancer = null;
    }
}
