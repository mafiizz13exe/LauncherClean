package com.mafiizz.doomtv;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Tela inicial: escolhe qual Doom jogar (Freedoom embutido + WADs originais do usuário). */
public class LauncherActivity extends Activity {

    private static class Game {
        final String title, path;
        Game(String title, String path) { this.title = title; this.path = path; }
        @Override public String toString() { return title; }
    }

    private final List<Game> games = new ArrayList<>();
    private ListView list;
    private TextView status;
    private ArrayAdapter<Game> adapter;
    private TextView sub;
    private final Handler handler = new Handler();
    private boolean cancelled = false;
    private boolean started = false;
    private Game mainGame = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final int dp = (int) getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(12, 8, 8));
        root.setPadding(64 * dp, 40 * dp, 64 * dp, 24 * dp);

        TextView title = new TextView(this);
        title.setText("DOOM TV");
        title.setTextColor(Color.rgb(220, 30, 20));
        title.setTextSize(44);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        sub = new TextView(this);
        sub.setText("Escolha o jogo (OK/Select para iniciar)");
        sub.setTextColor(Color.LTGRAY);
        sub.setTextSize(20);
        sub.setPadding(0, 4 * dp, 0, 20 * dp);
        root.addView(sub);

        list = new ListView(this);
        list.setDivider(new ColorDrawable(Color.rgb(40, 28, 28)));
        list.setDividerHeight(1);
        list.setSelector(new ColorDrawable(Color.rgb(120, 20, 15)));
        list.setItemsCanFocus(false);
        adapter = new ArrayAdapter<Game>(this, android.R.layout.simple_list_item_1, games) {
            @Override public View getView(int pos, View v, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, v, parent);
                t.setTextColor(Color.WHITE);
                t.setTextSize(26);
                t.setPadding(24 * dp, 16 * dp, 24 * dp, 16 * dp);
                t.setGravity(Gravity.CENTER_VERTICAL);
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> { cancelled = true; start(games.get(pos)); });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setTextColor(Color.GRAY);
        status.setTextSize(16);
        status.setText("Preparando arquivos…");
        root.addView(status);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 32
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, 1);
        }
        scan();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        scan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) list.requestFocus();
    }

    private void scan() {
        new Thread(() -> {
            final List<Game> found = new ArrayList<>();
            // 1) WADs originais do usuário (têm prioridade)
            List<File> dirs = new ArrayList<>();
            File ext = getExternalFilesDir(null);
            if (ext != null) dirs.add(ext);
            File sd = Environment.getExternalStorageDirectory();
            dirs.add(new File(sd, "Doom"));
            dirs.add(new File(sd, "Download"));
            dirs.add(new File(sd, "Downloads"));
            dirs.add(sd);
            String[][] known = {
                { "doom.wad", "The Ultimate Doom" },
                { "doom2.wad", "Doom II: Hell on Earth" },
                { "plutonia.wad", "Final Doom: The Plutonia Experiment" },
                { "tnt.wad", "Final Doom: TNT Evilution" },
                { "doom1.wad", "Doom (shareware)" },
            };
            for (File d : dirs) {
                File[] fs = d.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    for (String[] k : known) {
                        if (n.equals(k[0]) && f.isFile() && f.canRead() && !has(found, f.getAbsolutePath())) {
                            found.add(new Game(k[1] + "  (seu arquivo)", f.getAbsolutePath()));
                        }
                    }
                }
            }
            // 2) Jogos embutidos no APK
            String msg = "";
            try {
                File iw = new File(getFilesDir(), "iwads");
                iw.mkdirs();
                if (assetExists("doom2.wad") && !hasName(found, "doom2.wad")) {
                    found.add(0, new Game("DOOM II: Hell on Earth", copyAsset("doom2.wad", iw)));
                }
                found.add(new Game("Freedoom: Phase 1  (estilo Doom 1, 4 episódios)", copyAsset("freedoom1.wad", iw)));
                found.add(new Game("Freedoom: Phase 2  (estilo Doom II, 32 fases)", copyAsset("freedoom2.wad", iw)));
            } catch (Exception e) {
                msg = "Erro ao preparar os jogos: " + e;
            }
            // 3) doom2.wad sempre em primeiro (é o jogo principal)
            Game main = null;
            for (Game g : found) if (new File(g.path).getName().equalsIgnoreCase("doom2.wad")) { main = g; break; }
            if (main != null) { found.remove(main); found.add(0, main); }
            final Game fmain = main;
            final String fmsg = msg;
            runOnUiThread(() -> {
                games.clear();
                games.addAll(found);
                adapter.notifyDataSetChanged();
                status.setText(fmsg.isEmpty()
                    ? "Para usar o Doom original, copie doom.wad / doom2.wad para a pasta Doom do armazenamento (ou Download)."
                    : fmsg);
                list.requestFocus();
                if (!games.isEmpty()) list.setSelection(0);
                mainGame = fmain;
                if (fmain != null && fmsg.isEmpty() && !cancelled && !started) {
                    sub.setText("Iniciando DOOM II…  (toque, Voltar ou Menu para escolher outro jogo)");
                    handler.postDelayed(() -> {
                        if (!cancelled && !started) start(mainGame);
                    }, 3000);
                }
            });
        }).start();
    }

    private boolean hasName(List<Game> l, String name) {
        for (Game g : l) if (new File(g.path).getName().equalsIgnoreCase(name)) return true;
        return false;
    }

    private boolean assetExists(String name) {
        try (android.content.res.AssetFileDescriptor afd = getAssets().openFd(name)) {
            return afd.getLength() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // qualquer toque/tecla cancela o início automático e deixa escolher o jogo
    private void cancelAuto() {
        if (!cancelled && !started) {
            cancelled = true;
            sub.setText("Escolha o jogo (OK/Select para iniciar)");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getAction() == KeyEvent.ACTION_DOWN && !started && !cancelled && mainGame != null) {
            cancelAuto();
            if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK || ev.getKeyCode() == KeyEvent.KEYCODE_MENU) return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) cancelAuto();
        return super.dispatchTouchEvent(ev);
    }

    private boolean has(List<Game> l, String path) {
        for (Game g : l) if (g.path.equals(path)) return true;
        return false;
    }

    private String copyAsset(String name, File dir) throws Exception {
        File out = new File(dir, name);
        long size;
        try (android.content.res.AssetFileDescriptor afd = getAssets().openFd(name)) { size = afd.getLength(); }
        if (out.exists() && out.length() == size) return out.getAbsolutePath();
        try (InputStream in = getAssets().open(name); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out.getAbsolutePath();
    }

    private void start(Game g) {
        if (started || g == null) return;
        started = true;
        String base = new File(g.path).getName().replaceAll("\\.[^.]*$", "");
        File work = new File(getFilesDir(), "save-" + base);
        work.mkdirs();
        Intent i = new Intent(this, DoomActivity.class);
        i.putExtra("iwad", g.path);
        i.putExtra("workdir", work.getAbsolutePath());
        startActivity(i);
        finish();
    }
}
