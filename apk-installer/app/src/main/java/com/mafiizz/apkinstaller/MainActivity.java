package com.mafiizz.apkinstaller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.json.JSONObject;

/**
 * APK Installer para Fire TV / Android TV.
 * - Suporta APK simples e pacotes divididos (.xapk, .apks, .apkm), inclusive com arquivos OBB.
 * - Lista os APKs da pasta Downloads (e do resto do armazenamento / pendrive) e instala.
 * - Baixa um APK a partir de um link e instala em seguida.
 * Interface montada por código, toda navegável com o controle remoto (D-pad).
 */
public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 1;
    private static final int REQ_UNKNOWN = 2;
    private static final int MAX_FOUND = 300;
    private static final String ACTION_INSTALL_RESULT = "com.mafiizz.apkinstaller.INSTALL_RESULT";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<ApkItem> items = new ArrayList<>();

    private EditText urlInput;
    private ListView list;
    private TextView header;
    private TextView empty;
    private ApkAdapter adapter;

    private File pendingInstall;
    private boolean askedStorage = false;
    private volatile boolean scanning = false;
    private volatile boolean cancelDownload = false;

    private AlertDialog dlDialog;
    private TextView dlStatus;
    private ProgressBar dlBar;

    // ---------------------------------------------------------------- modelo

    private static class ApkItem {
        File file;
        long size;
        long modified;
        String label;
        String pkg;
        String version;
        boolean installed;
        boolean bundle;
        String installedVersion;

        String title() {
            return label != null && label.length() > 0 ? label : file.getName();
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        registerReceiver(installReceiver, new IntentFilter(ACTION_INSTALL_RESULT));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(installReceiver); } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureStorageThenScan(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) scan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UNKNOWN && pendingInstall != null) {
            File f = pendingInstall;
            pendingInstall = null;
            if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) {
                proceedInstall(f);
            } else {
                toast("Instalação não foi liberada. Ative \"Permitir desta fonte\" e tente de novo.");
            }
        }
    }

    // ---------------------------------------------------------------- interface

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);
        root.setPadding(dp(48), dp(28), dp(48), dp(20));

        TextView title = text("APK Installer", 30, Color.WHITE, true);
        TextView sub = text("Cole o link de um APK (ou pacote XAPK / APKS / APKM) para baixar e instalar, ou escolha um arquivo já baixado.",
                16, 0xFF90CAF9, false);
        root.addView(title);
        root.addView(sub);

        // linha 1: link + botão baixar
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setHint("Link do APK / XAPK / APKS / APKM (https://...)");
        urlInput.setHintTextColor(0xFF78909C);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setTextSize(18);
        urlInput.setPadding(dp(16), dp(12), dp(16), dp(12));
        urlInput.setBackground(stateBg(0xFF1C242B, 0xFF1C242B, 0xFF29B6F6));
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            startDownload();
            return true;
        });
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row1.addView(urlInput, urlLp);

        Button go = makeButton("Baixar e instalar");
        go.setOnClickListener(v -> startDownload());
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        goLp.leftMargin = dp(12);
        row1.addView(go, goLp);

        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row1Lp.topMargin = dp(16);
        root.addView(row1, row1Lp);

        // linha 2: atualizar + permissão
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        Button refresh = makeButton("Atualizar lista");
        refresh.setOnClickListener(v -> {
            askedStorage = false;
            ensureStorageThenScan(true);
        });
        row2.addView(refresh);

        Button perm = makeButton("Permitir instalação");
        perm.setOnClickListener(v -> openUnknownSourcesSettings());
        LinearLayout.LayoutParams permLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        permLp.leftMargin = dp(12);
        row2.addView(perm, permLp);

        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(10);
        root.addView(row2, row2Lp);

        header = text("Arquivos encontrados", 18, Color.WHITE, true);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(18);
        hLp.bottomMargin = dp(8);
        root.addView(header, hLp);

        // lista + mensagem de vazio
        FrameLayout box = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(6));
        list.setSelector(rounded(0x3329B6F6, 0xFF29B6F6, dp(2)));
        adapter = new ApkAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < items.size()) showApkDialog(items.get(position));
        });
        box.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        empty = text("Procurando APKs...", 18, 0xFF90A4AE, false);
        empty.setGravity(Gravity.CENTER);
        box.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);

        root.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private class ApkAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(16), dp(12), dp(16), dp(12));
                TextView t1 = text("", 20, Color.WHITE, true);
                TextView t2 = text("", 14, 0xFFB0BEC5, false);
                row.addView(t1);
                row.addView(t2);
                row.setTag(new TextView[]{t1, t2});
            } else {
                row = (LinearLayout) convertView;
            }
            TextView[] tv = (TextView[]) row.getTag();
            ApkItem it = items.get(position);
            tv[0].setText(it.title());

            StringBuilder sb = new StringBuilder();
            if (it.installed) {
                sb.append("Já instalado");
                if (it.installedVersion != null) sb.append(" (v").append(it.installedVersion).append(")");
                sb.append(" • ");
            }
            if (it.version != null) sb.append("v").append(it.version).append(" • ");
            sb.append(formatSize(it.size)).append(" • ").append(shortFolder(it.file.getParent()));
            if (it.label != null) sb.append("\n").append(it.file.getName());
            tv[1].setText(sb.toString());
            return row;
        }
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button makeButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(18);
        b.setFocusable(true);
        b.setPadding(dp(24), dp(12), dp(24), dp(12));
        b.setBackground(stateBg(0xFF263238, 0xFF2979FF, 0xFFFFFFFF));
        return b;
    }

    private StateListDrawable stateBg(int normal, int focusedFill, int focusedStroke) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, rounded(focusedFill, focusedStroke, dp(2)));
        sl.addState(new int[]{android.R.attr.state_pressed}, rounded(focusedFill, 0, 0));
        sl.addState(new int[]{}, rounded(normal, 0, 0));
        return sl;
    }

    private GradientDrawable rounded(int fill, int stroke, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(10));
        if (strokeWidth > 0) g.setStroke(strokeWidth, stroke);
        return g;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- permissões e busca de APKs

    private boolean hasStoragePerm() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureStorageThenScan(boolean allowAsk) {
        if (hasStoragePerm()) {
            scan();
        } else if (allowAsk && !askedStorage && Build.VERSION.SDK_INT >= 23) {
            askedStorage = true;
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        } else {
            scan();
        }
    }

    private void scan() {
        if (scanning) return;
        scanning = true;
        header.setText("Procurando arquivos...");
        new Thread(() -> {
            final List<ApkItem> found = new ArrayList<>();
            try {
                Set<String> seen = new HashSet<>();
                List<File> roots = new ArrayList<>();
                roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
                roots.add(Environment.getExternalStorageDirectory());
                File[] vols = new File("/storage").listFiles();
                if (vols != null) {
                    for (File v : vols) {
                        String n = v.getName();
                        if (!n.equals("emulated") && !n.equals("self")) roots.add(v);
                    }
                }
                for (int i = 0; i < roots.size(); i++) {
                    walk(roots.get(i), 0, i == 0 ? 6 : 3, found, seen);
                }
                Collections.sort(found, new Comparator<ApkItem>() {
                    @Override
                    public int compare(ApkItem a, ApkItem b) {
                        return Long.compare(b.modified, a.modified);
                    }
                });
                PackageManager pm = getPackageManager();
                for (ApkItem it : found) enrich(pm, it);
            } catch (Throwable ignored) {
            }
            ui.post(() -> {
                scanning = false;
                showItems(found);
            });
        }).start();
    }

    private void walk(File dir, int depth, int maxDepth, List<ApkItem> out, Set<String> seen) {
        if (dir == null || out.size() >= MAX_FOUND) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (out.size() >= MAX_FOUND) return;
            String name = f.getName();
            if (f.isDirectory()) {
                if (depth + 1 > maxDepth) continue;
                if (name.startsWith(".") || name.equals("Android") || name.equals("LOST.DIR")) continue;
                walk(f, depth + 1, maxDepth, out, seen);
            } else if (isApkLike(name)) {
                String key;
                try {
                    key = f.getCanonicalPath();
                } catch (IOException e) {
                    key = f.getAbsolutePath();
                }
                if (!seen.add(key)) continue;
                ApkItem it = new ApkItem();
                it.file = f;
                it.size = f.length();
                it.modified = f.lastModified();
                it.bundle = isBundleName(name);
                out.add(it);
            }
        }
    }

    private void enrich(PackageManager pm, ApkItem it) {
        if (it.bundle) {
            enrichBundle(pm, it);
            return;
        }
        try {
            String path = it.file.getAbsolutePath();
            PackageInfo pi = pm.getPackageArchiveInfo(path, 0);
            if (pi == null) return;
            it.pkg = pi.packageName;
            it.version = pi.versionName;
            if (pi.applicationInfo != null) {
                pi.applicationInfo.sourceDir = path;
                pi.applicationInfo.publicSourceDir = path;
                CharSequence l = pi.applicationInfo.loadLabel(pm);
                if (l != null) it.label = l.toString();
            }
            try {
                PackageInfo inst = pm.getPackageInfo(pi.packageName, 0);
                it.installed = true;
                it.installedVersion = inst.versionName;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private void showItems(List<ApkItem> found) {
        items.clear();
        items.addAll(found);
        adapter.notifyDataSetChanged();
        header.setText("Arquivos encontrados: " + items.size());
        if (!hasStoragePerm()) {
            empty.setText("Sem permissão para ler os arquivos.\nAperte \"Atualizar lista\" e permita o acesso.");
        } else {
            empty.setText("Nenhum APK, XAPK, APKS ou APKM encontrado.\nBaixe um pelo link acima ou copie o arquivo para a pasta Downloads.");
        }
    }

    // ---------------------------------------------------------------- ações sobre um APK

    private void showApkDialog(final ApkItem it) {
        String msg = it.file.getAbsolutePath() + "\n"
                + formatSize(it.size) + " • "
                + new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date(it.modified));
        if (it.installed) {
            msg += "\nEste app já está instalado"
                    + (it.installedVersion != null ? " (v" + it.installedVersion + ")" : "") + ".";
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(it.title())
                .setMessage(msg)
                .setPositiveButton("Instalar", (dialog, which) -> installApk(it.file))
                .setNegativeButton("Apagar arquivo", (dialog, which) -> confirmDelete(it))
                .setNeutralButton("Voltar", null)
                .create();
        d.show();
        Button pos = d.getButton(AlertDialog.BUTTON_POSITIVE);
        if (pos != null) pos.requestFocus();
    }

    private void confirmDelete(final ApkItem it) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Apagar arquivo?")
                .setMessage(it.file.getName())
                .setPositiveButton("Apagar", (dialog, which) -> {
                    if (it.file.delete()) {
                        toast("Arquivo apagado");
                    } else {
                        toast("Não foi possível apagar o arquivo");
                    }
                    scan();
                })
                .setNegativeButton("Cancelar", null)
                .create();
        d.show();
        Button neg = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (neg != null) neg.requestFocus();
    }

    // ---------------------------------------------------------------- instalação

    private void installApk(final File f) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = f;
            new AlertDialog.Builder(this)
                    .setTitle("Permitir instalação")
                    .setMessage("Para instalar APKs, ative a opção \"Permitir desta fonte\" para o APK Installer na próxima tela e depois volte com o botão Voltar do controle.")
                    .setPositiveButton("Abrir configuração", (dialog, which) -> openUnknownSourcesSettings())
                    .setNegativeButton("Cancelar", (dialog, which) -> pendingInstall = null)
                    .show();
            return;
        }
        proceedInstall(f);
    }

    private void proceedInstall(File f) {
        if (isBundleName(f.getName())) installBundle(f);
        else launchInstaller(f);
    }

    private void openUnknownSourcesSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_UNKNOWN);
            } else {
                startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            }
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e2) {
                toast("Abra Configurações > Meu Fire TV > Opções do desenvolvedor e libere \"Instalar apps desconhecidos\".");
            }
        }
    }

    private void launchInstaller(File f) {
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) {
                uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            } else {
                uri = Uri.fromFile(f);
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Não foi possível abrir o instalador: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- download por link

    private void startDownload() {
        String raw = urlInput.getText().toString().trim();
        if (raw.length() == 0) {
            toast("Cole ou digite o link do APK primeiro");
            return;
        }
        if (!raw.contains("://")) raw = "https://" + raw;
        if (!hasStoragePerm()) {
            askedStorage = false;
            ensureStorageThenScan(true);
            toast("Permita o acesso aos arquivos e tente de novo");
            return;
        }
        final String link = raw;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        dlStatus = text("Conectando...", 16, Color.WHITE, false);
        dlBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        dlBar.setMax(1000);
        dlBar.setIndeterminate(true);
        box.addView(dlStatus);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(12);
        box.addView(dlBar, barLp);

        cancelDownload = false;
        dlDialog = new AlertDialog.Builder(this)
                .setTitle("Baixando arquivo")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Cancelar", (dialog, which) -> cancelDownload = true)
                .create();
        dlDialog.show();

        new Thread(() -> doDownload(link)).start();
    }

    private void doDownload(String link) {
        HttpURLConnection c = null;
        File out = null;
        try {
            String cur = link;
            int redirects = 0;
            while (true) {
                URL u = new URL(cur);
                c = (HttpURLConnection) u.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 9; AFTMM) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0 Mobile Safari/537.36");
                c.setRequestProperty("Accept", "*/*");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && code != 304) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    redirects++;
                    if (loc == null || redirects > 10) throw new IOException("Redirecionamento inválido");
                    cur = new URL(new URL(cur), loc).toString();
                    continue;
                }
                if (code != 200) throw new IOException("O servidor respondeu com o código " + code);
                break;
            }

            long total = -1;
            try {
                String cl = c.getHeaderField("Content-Length");
                if (cl != null) total = Long.parseLong(cl.trim());
            } catch (Exception ignored) {
            }

            String name = fileNameFrom(c, cur);
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            out = uniqueFile(dir, name);

            final long totalF = total;
            InputStream in = c.getInputStream();
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            long lastUi = 0;
            int n;
            try {
                while ((n = in.read(buf)) != -1) {
                    if (cancelDownload) break;
                    os.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 200) {
                        lastUi = now;
                        final long d = done;
                        ui.post(() -> updateProgress(d, totalF));
                    }
                }
            } finally {
                try { os.close(); } catch (IOException ignored) { }
                try { in.close(); } catch (IOException ignored) { }
            }

            if (cancelDownload) {
                out.delete();
                ui.post(this::dismissDownload);
                return;
            }
            if (!looksLikeApk(out)) {
                out.delete();
                throw new IOException("O arquivo baixado não parece ser um APK/XAPK/APKS/APKM. Confira se o link leva direto ao arquivo.");
            }

            final File finalOut = out;
            ui.post(() -> {
                dismissDownload();
                toast("Download concluído: " + finalOut.getName());
                scan();
                installApk(finalOut);
            });
        } catch (final Exception e) {
            if (out != null) out.delete();
            ui.post(() -> {
                dismissDownload();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Falha no download")
                        .setMessage(e.getMessage() != null ? e.getMessage() : e.toString())
                        .setPositiveButton("OK", null)
                        .show();
            });
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void updateProgress(long done, long total) {
        if (dlStatus == null || dlBar == null) return;
        if (total > 0) {
            dlBar.setIndeterminate(false);
            dlBar.setProgress((int) (done * 1000L / total));
            dlStatus.setText("Baixando... " + formatSize(done) + " de " + formatSize(total)
                    + " (" + (done * 100L / total) + "%)");
        } else {
            dlBar.setIndeterminate(true);
            dlStatus.setText("Baixando... " + formatSize(done));
        }
    }

    private void dismissDownload() {
        try {
            if (dlDialog != null && dlDialog.isShowing()) dlDialog.dismiss();
        } catch (Exception ignored) {
        }
        dlDialog = null;
    }

    private boolean looksLikeApk(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return in.read() == 'P' && in.read() == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    private String fileNameFrom(HttpURLConnection c, String url) {
        String name = null;
        String cd = c.getHeaderField("Content-Disposition");
        if (cd != null) {
            int i = cd.toLowerCase(Locale.ROOT).indexOf("filename=");
            if (i >= 0) {
                name = cd.substring(i + 9).trim();
                int semi = name.indexOf(';');
                if (semi >= 0) name = name.substring(0, semi);
                name = name.replace("\"", "").trim();
            }
        }
        if (name == null || name.length() == 0) {
            String seg = Uri.parse(url).getLastPathSegment();
            if (seg != null) name = seg;
        }
        if (name != null) {
            try {
                name = URLDecoder.decode(name, "UTF-8");
            } catch (Exception ignored) {
            }
            name = name.replaceAll("[^A-Za-z0-9._()-]", "_").trim();
        }
        if (name == null || name.length() == 0) name = "app.apk";
        if (!isApkLike(name)) name = name + ".apk";
        return name;
    }

    private File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".apk";
        int i = 1;
        while (true) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
            i++;
        }
    }

    // ---------------------------------------------------------------- pacotes .xapk / .apks / .apkm

    private static boolean isBundleName(String n) {
        n = n.toLowerCase(Locale.ROOT);
        return n.endsWith(".xapk") || n.endsWith(".apks") || n.endsWith(".apkm");
    }

    private static boolean isApkLike(String n) {
        return n.toLowerCase(Locale.ROOT).endsWith(".apk") || isBundleName(n);
    }

    private static String baseName(String path) {
        String p = path.replace('\\', '/');
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) bo.write(b, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && a.length() > 0) return a;
        return b != null && b.length() > 0 ? b : null;
    }

    private ZipEntry findBase(ZipFile zf) {
        ZipEntry fallback = null;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            String n = baseName(e.getName()).toLowerCase(Locale.ROOT);
            if (!n.endsWith(".apk")) continue;
            if (n.equals("base.apk") || n.equals("base-master.apk")) return e;
            if (!n.contains("config") && fallback == null) fallback = e;
        }
        return fallback;
    }

    private JSONObject bundleMeta(ZipFile zf) {
        try {
            ZipEntry me = zf.getEntry("manifest.json");
            if (me == null) me = zf.getEntry("info.json");
            if (me == null) return null;
            return new JSONObject(readAll(zf.getInputStream(me)));
        } catch (Throwable t) {
            return null;
        }
    }

    private String bundlePackage(ZipFile zf) {
        JSONObject j = bundleMeta(zf);
        if (j == null) return null;
        return firstNonEmpty(j.optString("package_name"), j.optString("pname"));
    }

    private void enrichBundle(PackageManager pm, ApkItem it) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(it.file);
            JSONObject j = bundleMeta(zf);
            if (j != null) {
                it.pkg = firstNonEmpty(j.optString("package_name"), j.optString("pname"));
                it.label = firstNonEmpty(j.optString("name"), j.optString("app_name"));
                it.version = firstNonEmpty(j.optString("version_name"), j.optString("versionname"));
            }
            if (it.pkg == null || it.label == null) {
                ZipEntry base = findBase(zf);
                if (base != null) {
                    File tmp = new File(getCacheDir(), "probe.apk");
                    copyEntry(zf, base, tmp);
                    PackageInfo pi = pm.getPackageArchiveInfo(tmp.getAbsolutePath(), 0);
                    if (pi != null) {
                        if (it.pkg == null) it.pkg = pi.packageName;
                        if (it.version == null) it.version = pi.versionName;
                        if (it.label == null && pi.applicationInfo != null) {
                            pi.applicationInfo.sourceDir = tmp.getAbsolutePath();
                            pi.applicationInfo.publicSourceDir = tmp.getAbsolutePath();
                            CharSequence l = pi.applicationInfo.loadLabel(pm);
                            if (l != null) it.label = l.toString();
                        }
                    }
                    tmp.delete();
                }
            }
            if (it.pkg != null) {
                try {
                    PackageInfo inst = pm.getPackageInfo(it.pkg, 0);
                    it.installed = true;
                    it.installedVersion = inst.versionName;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) try { zf.close(); } catch (IOException ignored) { }
        }
    }

    private void copyEntry(ZipFile zf, ZipEntry e, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        InputStream in = zf.getInputStream(e);
        OutputStream os = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
        } finally {
            try { os.close(); } catch (IOException ignored) { }
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    /** Decide se um split (config.*) serve para este aparelho: ABI, densidade de tela ou idioma. */
    private boolean wantSplit(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".apk")) n = n.substring(0, n.length() - 4);
        int idx = n.lastIndexOf("config.");
        if (idx < 0) return true; // base ou split de recurso/feature: instala
        String tok = n.substring(idx + 7).replace('-', '_');

        String[] abis = {"arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips64", "mips"};
        for (String a : abis) {
            if (tok.equals(a)) {
                for (String sup : Build.SUPPORTED_ABIS) {
                    if (sup.toLowerCase(Locale.ROOT).replace('-', '_').equals(a)) return true;
                }
                return false;
            }
        }
        String[] dens = {"ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi"};
        for (String d : dens) {
            if (tok.equals(d)) {
                if (d.equals("nodpi")) return true;
                return d.equals(deviceDensityBucket());
            }
        }
        String lang = Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT);
        String t = tok.split("_")[0];
        return t.equals(lang) || t.equals("en");
    }

    private String deviceDensityBucket() {
        int d = getResources().getDisplayMetrics().densityDpi;
        if (d <= 120) return "ldpi";
        if (d <= 160) return "mdpi";
        if (d <= 213) return "tvdpi";
        if (d <= 240) return "hdpi";
        if (d <= 320) return "xhdpi";
        if (d <= 480) return "xxhdpi";
        return "xxxhdpi";
    }

    private void installBundle(final File f) {
        final TextView status = text("Preparando instalação...", 16, Color.WHITE, false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        box.addView(status);
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        box.addView(pb, lp);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Instalando pacote")
                .setView(box)
                .setCancelable(false)
                .create();
        dlg.show();

        new Thread(() -> {
            ZipFile zf = null;
            PackageInstaller.Session session = null;
            try {
                zf = new ZipFile(f);
                String pkg = bundlePackage(zf);
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                if (pkg != null) sp.setAppPackageName(pkg);
                int id = pi.createSession(sp);
                session = pi.openSession(id);

                int count = 0;
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    String name = e.getName().replace('\\', '/');
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (name.contains("..")) continue;
                    if (lower.endsWith(".apk")) {
                        if (!wantSplit(baseName(name))) continue;
                        final String shown = baseName(name);
                        ui.post(() -> status.setText("Copiando " + shown + "..."));
                        InputStream in = zf.getInputStream(e);
                        OutputStream os = session.openWrite("split_" + count + ".apk", 0, e.getSize());
                        try {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                            session.fsync(os);
                        } finally {
                            try { os.close(); } catch (IOException ignored) { }
                            try { in.close(); } catch (IOException ignored) { }
                        }
                        count++;
                    } else if (lower.endsWith(".obb")) {
                        File dest;
                        if (name.startsWith("Android/obb/")) {
                            dest = new File(Environment.getExternalStorageDirectory(), name);
                        } else if (pkg != null) {
                            dest = new File(Environment.getExternalStorageDirectory(),
                                    "Android/obb/" + pkg + "/" + baseName(name));
                        } else {
                            continue;
                        }
                        final String shown = baseName(name);
                        ui.post(() -> status.setText("Copiando dados do app (" + shown + ")..."));
                        copyEntry(zf, e, dest);
                    }
                }
                if (count == 0) throw new IOException("Nenhum APK encontrado dentro do pacote.");

                ui.post(() -> status.setText("Abrindo instalador..."));
                Intent cb = new Intent(ACTION_INSTALL_RESULT).setPackage(getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pend = PendingIntent.getBroadcast(this, id, cb, flags);
                session.commit(pend.getIntentSender());
                session.close();
                session = null;
                ui.post(dlg::dismiss);
            } catch (final Exception e) {
                if (session != null) {
                    try { session.abandon(); } catch (Exception ignored) { }
                }
                ui.post(() -> {
                    try { dlg.dismiss(); } catch (Exception ignored) { }
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Falha ao preparar o pacote")
                            .setMessage(e.getMessage() != null ? e.getMessage() : e.toString())
                            .setPositiveButton("OK", null)
                            .show();
                });
            } finally {
                if (zf != null) try { zf.close(); } catch (IOException ignored) { }
            }
        }).start();
    }

    private final BroadcastReceiver installReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(confirm); } catch (Exception e) { toast("Não foi possível abrir a confirmação: " + e.getMessage()); }
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                toast("App instalado com sucesso");
                scan();
            } else {
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Falha na instalação")
                        .setMessage(m != null ? m : "Código " + st)
                        .setPositiveButton("OK", null)
                        .show();
            }
        }
    };

    // ---------------------------------------------------------------- utilidades

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.getDefault(), "%.2f GB", bytes / 1073741824.0);
    }

    private static String shortFolder(String path) {
        if (path == null) return "";
        return path.replace("/storage/emulated/0", "Interno").replace("/sdcard", "Interno");
    }
}
