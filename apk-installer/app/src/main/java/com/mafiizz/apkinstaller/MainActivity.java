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
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.widget.ImageView;
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
        Drawable icon;
        long versionCode;
        long installedCode;

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

    private static final int C_BG_TOP = 0xFF0A1020;
    private static final int C_BG_BOTTOM = 0xFF13203B;
    private static final int C_SURFACE = 0xFF172238;
    private static final int C_SURFACE_HI = 0xFF22335A;
    private static final int C_ACCENT = 0xFF4F8CFF;
    private static final int C_ACCENT_2 = 0xFF22D3EE;
    private static final int C_OK = 0xFF34D399;
    private static final int C_WARN = 0xFFFBBF24;
    private static final int C_BUNDLE = 0xFFA78BFA;
    private static final int C_TEXT = 0xFFF1F5F9;
    private static final int C_MUTED = 0xFF94A3B8;
    private static final int C_DIM = 0xFF64748B;

    private TextView countChip;

    private void buildUi() {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{C_BG_TOP, C_BG_BOTTOM});
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(bg);
        root.setPadding(dp(56), dp(30), dp(56), dp(18));

        // ---- cabeçalho: logo + título + contador
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams(dp(60), dp(60)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("APK Installer", 32, C_TEXT, true));
        titles.addView(text("Instale APK, XAPK, APKS e APKM direto na sua TV", 15, C_MUTED, false));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(16);
        head.addView(titles, tLp);
        root.addView(head);

        // ---- cartão: baixar por link
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(C_SURFACE, 0, 0, dp(18)));
        card.setPadding(dp(22), dp(18), dp(22), dp(20));

        card.addView(text("Baixar por link", 17, C_TEXT, true));
        TextView cardSub = text("Cole o endereço direto de um APK, XAPK, APKS ou APKM.", 13, C_MUTED, false);
        card.addView(cardSub);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlInput.setHint("https://exemplo.com/app.apk");
        urlInput.setHintTextColor(C_DIM);
        urlInput.setTextColor(C_TEXT);
        urlInput.setTextSize(18);
        urlInput.setPadding(dp(18), dp(12), dp(18), dp(12));
        urlInput.setBackground(stateBg(0xFF0F1830, 0xFF0F1830, C_ACCENT_2, dp(12)));
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            startDownload();
            return true;
        });
        row1.addView(urlInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button go = makeButton("Baixar e instalar", true);
        go.setOnClickListener(v -> startDownload());
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        goLp.leftMargin = dp(14);
        row1.addView(go, goLp);

        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row1Lp.topMargin = dp(12);
        card.addView(row1, row1Lp);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(18);
        root.addView(card, cardLp);

        // ---- título da seção + contador + ações
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.HORIZONTAL);
        sec.setGravity(Gravity.CENTER_VERTICAL);

        header = text("Arquivos encontrados", 20, C_TEXT, true);
        sec.addView(header);

        countChip = new TextView(this);
        styleChip(countChip, "0", C_ACCENT_2);
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ccLp.leftMargin = dp(12);
        sec.addView(countChip, ccLp);

        View spacer = new View(this);
        sec.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        Button refresh = makeButton("Atualizar lista", false);
        refresh.setOnClickListener(v -> {
            askedStorage = false;
            ensureStorageThenScan(true);
        });
        sec.addView(refresh);

        Button perm = makeButton("Permitir instalação", false);
        perm.setOnClickListener(v -> openUnknownSourcesSettings());
        LinearLayout.LayoutParams permLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        permLp.leftMargin = dp(12);
        sec.addView(perm, permLp);

        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secLp.topMargin = dp(22);
        secLp.bottomMargin = dp(10);
        root.addView(sec, secLp);

        // ---- lista + estado vazio
        FrameLayout box = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(10));
        list.setClipToPadding(false);
        list.setPadding(dp(4), dp(4), dp(4), dp(4));
        list.setVerticalScrollBarEnabled(false);
        list.setDrawSelectorOnTop(true);
        list.setSelector(rounded(0x1A22D3EE, C_ACCENT_2, dp(3), dp(16)));
        adapter = new ApkAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < items.size()) showApkDialog(items.get(position));
        });
        box.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER);
        TextView badge = text("APK", 26, C_ACCENT_2, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(rounded(C_SURFACE, 0, 0, dp(28)));
        emptyBox.addView(badge, new LinearLayout.LayoutParams(dp(96), dp(96)));
        empty = text("Procurando APKs...", 17, C_MUTED, false);
        empty.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        eLp.topMargin = dp(16);
        emptyBox.addView(empty, eLp);
        box.addView(emptyBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(emptyBox);

        root.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = text("Controle remoto:  setas para navegar  •  OK para selecionar  •  Voltar para sair",
                12, C_DIM, false);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(8);
        root.addView(hint, hLp);

        setContentView(root);

        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(350).start();
    }

    private static class RowHolder {
        FrameLayout iconBox;
        ImageView icon;
        TextView letter;
        TextView title;
        TextView sub;
        TextView typeChip;
        TextView statusChip;
    }

    private class ApkAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            RowHolder h;
            if (convertView == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(18), dp(14), dp(18), dp(14));
                row.setBackground(rounded(C_SURFACE, 0, 0, dp(16)));
                h = new RowHolder();

                h.iconBox = new FrameLayout(MainActivity.this);
                h.icon = new ImageView(MainActivity.this);
                h.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                h.iconBox.addView(h.icon, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                h.letter = text("", 24, Color.WHITE, true);
                h.letter.setGravity(Gravity.CENTER);
                h.iconBox.addView(h.letter, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                row.addView(h.iconBox, new LinearLayout.LayoutParams(dp(56), dp(56)));

                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                h.title = text("", 20, C_TEXT, true);
                h.title.setSingleLine(true);
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                h.sub = text("", 13, C_MUTED, false);
                h.sub.setMaxLines(2);
                h.sub.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(h.title);
                col.addView(h.sub);
                LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                colLp.leftMargin = dp(16);
                colLp.rightMargin = dp(12);
                row.addView(col, colLp);

                LinearLayout chips = new LinearLayout(MainActivity.this);
                chips.setOrientation(LinearLayout.HORIZONTAL);
                chips.setGravity(Gravity.CENTER_VERTICAL);
                h.statusChip = new TextView(MainActivity.this);
                h.typeChip = new TextView(MainActivity.this);
                chips.addView(h.statusChip);
                LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tcLp.leftMargin = dp(8);
                chips.addView(h.typeChip, tcLp);
                row.addView(chips);

                row.setTag(h);
            } else {
                row = (LinearLayout) convertView;
                h = (RowHolder) row.getTag();
            }

            ApkItem it = items.get(position);
            h.title.setText(it.title());

            if (it.icon != null) {
                h.icon.setVisibility(View.VISIBLE);
                h.icon.setImageDrawable(it.icon);
                h.letter.setVisibility(View.GONE);
                h.iconBox.setBackground(null);
            } else {
                h.icon.setVisibility(View.GONE);
                h.letter.setVisibility(View.VISIBLE);
                String t = it.title();
                h.letter.setText(t.length() > 0 ? t.substring(0, 1).toUpperCase(Locale.getDefault()) : "?");
                h.iconBox.setBackground(rounded(it.bundle ? 0xFF5B4B9A : 0xFF2F5FB8, 0, 0, dp(14)));
            }

            StringBuilder sb = new StringBuilder();
            if (it.version != null) sb.append("v").append(it.version).append("  •  ");
            sb.append(formatSize(it.size)).append("  •  ").append(shortFolder(it.file.getParent()));
            if (it.label != null) sb.append("\n").append(it.file.getName());
            h.sub.setText(sb.toString());

            styleChip(h.typeChip, typeName(it.file.getName()), it.bundle ? C_BUNDLE : C_ACCENT);

            if (it.installed) {
                boolean newer = it.versionCode > 0 && it.installedCode > 0 && it.versionCode > it.installedCode;
                boolean older = it.versionCode > 0 && it.installedCode > 0 && it.versionCode < it.installedCode;
                if (newer) styleChip(h.statusChip, "Atualização", C_WARN);
                else if (older) styleChip(h.statusChip, "Versão antiga", C_DIM);
                else styleChip(h.statusChip, "Instalado", C_OK);
                h.statusChip.setVisibility(View.VISIBLE);
            } else {
                h.statusChip.setVisibility(View.GONE);
            }
            return row;
        }
    }

    private static String typeName(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1).toUpperCase(Locale.ROOT) : "APK";
    }

    private void styleChip(TextView t, String s, int color) {
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setPadding(dp(12), dp(4), dp(12), dp(4));
        t.setBackground(rounded((color & 0x00FFFFFF) | 0x26000000, 0, 0, dp(20)));
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button makeButton(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setFocusable(true);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(24), dp(12), dp(24), dp(12));
        b.setStateListAnimator(null);
        b.setBackground(buttonBg(primary));
        b.setOnFocusChangeListener((v, has) ->
                v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120).start());
        return b;
    }

    private StateListDrawable buttonBg(boolean primary) {
        StateListDrawable sl = new StateListDrawable();
        GradientDrawable focused;
        GradientDrawable normal;
        if (primary) {
            focused = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF6AA2FF, 0xFF4BE3F7});
            normal = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{C_ACCENT, 0xFF1BA9C4});
        } else {
            focused = new GradientDrawable();
            focused.setColor(0xFF31497F);
            normal = new GradientDrawable();
            normal.setColor(C_SURFACE_HI);
        }
        focused.setCornerRadius(dp(12));
        focused.setStroke(dp(2), Color.WHITE);
        normal.setCornerRadius(dp(12));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0xFF2B63D9);
        pressed.setCornerRadius(dp(12));
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sl.addState(new int[]{}, normal);
        return sl;
    }

    private StateListDrawable stateBg(int normal, int focusedFill, int focusedStroke, int radius) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, rounded(focusedFill, focusedStroke, dp(2), radius));
        sl.addState(new int[]{}, rounded(normal, 0, 0, radius));
        return sl;
    }

    private GradientDrawable rounded(int fill, int stroke, int strokeWidth, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeWidth > 0) g.setStroke(strokeWidth, stroke);
        return g;
    }

    /** Diálogos escuros, combinando com o resto do app. */
    private AlertDialog.Builder dlg() {
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
    }

    private void tintProgress(ProgressBar pb) {
        ColorStateList c = ColorStateList.valueOf(C_ACCENT_2);
        pb.setProgressTintList(c);
        pb.setIndeterminateTintList(c);
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
            it.versionCode = pi.versionCode;
            if (pi.applicationInfo != null) {
                pi.applicationInfo.sourceDir = path;
                pi.applicationInfo.publicSourceDir = path;
                CharSequence l = pi.applicationInfo.loadLabel(pm);
                if (l != null) it.label = l.toString();
                try { it.icon = pi.applicationInfo.loadIcon(pm); } catch (Throwable ignored) { }
            }
            try {
                PackageInfo inst = pm.getPackageInfo(pi.packageName, 0);
                it.installed = true;
                it.installedVersion = inst.versionName;
                it.installedCode = inst.versionCode;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private void showItems(List<ApkItem> found) {
        items.clear();
        items.addAll(found);
        adapter.notifyDataSetChanged();
        header.setText("Arquivos encontrados");
        styleChip(countChip, String.valueOf(items.size()), C_ACCENT_2);
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
        AlertDialog d = dlg()
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
        AlertDialog d = dlg()
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
            dlg()
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
        tintProgress(dlBar);
        box.addView(dlStatus);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(12);
        box.addView(dlBar, barLp);

        cancelDownload = false;
        dlDialog = dlg()
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
                dlg()
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
                try { it.versionCode = Long.parseLong(firstNonEmpty(j.optString("version_code"), j.optString("versioncode"))); }
                catch (Exception ignored) { }
            }
            it.icon = bundleIcon(zf);
            if (it.pkg == null || it.label == null) {
                ZipEntry base = findBase(zf);
                if (base != null) {
                    File tmp = new File(getCacheDir(), "probe.apk");
                    copyEntry(zf, base, tmp);
                    PackageInfo pi = pm.getPackageArchiveInfo(tmp.getAbsolutePath(), 0);
                    if (pi != null) {
                        if (it.pkg == null) it.pkg = pi.packageName;
                        if (it.version == null) it.version = pi.versionName;
                        if (it.versionCode == 0) it.versionCode = pi.versionCode;
                        if (it.label == null && pi.applicationInfo != null) {
                            pi.applicationInfo.sourceDir = tmp.getAbsolutePath();
                            pi.applicationInfo.publicSourceDir = tmp.getAbsolutePath();
                            CharSequence l = pi.applicationInfo.loadLabel(pm);
                            if (l != null) it.label = l.toString();
                        }
                        if (it.icon == null && pi.applicationInfo != null) {
                            try { it.icon = pi.applicationInfo.loadIcon(pm); } catch (Throwable ignored) { }
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
                    it.installedCode = inst.versionCode;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) try { zf.close(); } catch (IOException ignored) { }
        }
    }

    /** XAPK costuma trazer um icon.png na raiz do pacote. */
    private Drawable bundleIcon(ZipFile zf) {
        try {
            ZipEntry ie = zf.getEntry("icon.png");
            if (ie == null || ie.getSize() > 4L * 1024 * 1024) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            Bitmap bmp = BitmapFactory.decodeStream(zf.getInputStream(ie), null, o);
            return bmp != null ? new BitmapDrawable(getResources(), bmp) : null;
        } catch (Throwable t) {
            return null;
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
        tintProgress(pb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        box.addView(pb, lp);
        final AlertDialog dlg = dlg()
                .setTitle("Instalando pacote")
                .setView(box)
                .setCancelable(false)
                .create();
        progressDlg.show();

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
                ui.post(progressDlg::dismiss);
            } catch (final Exception e) {
                if (session != null) {
                    try { session.abandon(); } catch (Exception ignored) { }
                }
                ui.post(() -> {
                    try { progressDlg.dismiss(); } catch (Exception ignored) { }
                    dlg()
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
                dlg()
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
