package com.tj.skyrim;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Point;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/* Start screen, in the spirit of the PC SkyrimLauncher: pick the folder that
 * holds the Skyrim install, set the graphics options, press Play. The options
 * are written to SkyrimPrefs.ini (where the game reads them) before the game
 * starts; the folder is passed to the native side as argv[1]. */
public class LauncherActivity extends Activity {
    private static final int REQ_FOLDER = 1, REQ_PERM = 2;
    private SharedPreferences prefs;
    private TextView folderText, folderStatus;
    private Button playButton;
    private boolean pendingPick, pendingPlay;  /* continue once the storage prompt is answered */
    private boolean askedAllFiles;
    private final Map<String, Spinner> spinners = new LinkedHashMap<>();
    private final Map<String, CheckBox> checks = new LinkedHashMap<>();

    /* option key -> labels, and the INI values each label stands for */
    private static final String[] PRESET_FILES = {"low.ini", "medium.ini", "high.ini", "VeryHigh.ini"};
    private static final int[] MODE_HEIGHTS = {1080, 900, 720, 648, 576, 540, 480, 432, 360, 288, 270, 216, 180, 144, 108};

    // Aspect ratio of the picture. Very wide phones (20:9 and up) show the game
    // at 16:9 or so with black bars instead of stretching its UI to the edges.
    private static final int[][] ASPECT_V = {{16, 9}, {16, 10}, {4, 3}, {21, 9}, {0, 0}};

    private int[] screen() {
        Point sz = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(sz);
        return new int[]{Math.max(sz.x, sz.y), Math.min(sz.x, sz.y)};
    }

    // Must match bridge_scaled_mode in runtime/d3d9_bridge.cpp: the game only
    // accepts a size from the display mode list.
    private int[][] modes(int aspect) {
        int[] s = screen();
        int w = s[0], h = s[1];
        int an = ASPECT_V[aspect][0], ad = ASPECT_V[aspect][1];
        if (an == 0) { an = w; ad = h; }
        int top = (int) Math.min(h, (long) w * ad / an);
        java.util.ArrayList<int[]> l = new java.util.ArrayList<>();
        l.add(new int[]{(int) ((long) top * an / ad) & ~1, top});
        for (int mh : MODE_HEIGHTS)
            if (mh < top) l.add(new int[]{(int) ((long) mh * an / ad) & ~1, mh});
        return l.toArray(new int[0][]);
    }

    private String[] modeNames(int[][] m) {
        String[] r = new String[m.length];
        for (int i = 0; i < m.length; i++)
            r[i] = String.format(Locale.getDefault(), getString(R.string.resolution_format), m[i][0], m[i][1]);
        return r;
    }

    private int defaultMode(int[][] m) {
        for (int i = 0; i < m.length; i++) if (m[i][1] <= 540) return i;
        return m.length - 1;
    }
    private static final int[] AA_V = {1, 2, 4, 8};
    private static final int[] ANISO_V = {1, 2, 4, 8, 16};
    private static final int[] TEXQ_V = {2, 1, 0};  // iTexMipMapSkip
    private static final int[] SHADOW_V = {512, 1024, 2048, 4096};
    private static final int[] DECALS_V = {0, 100, 250, 1000};
    private static final String[] FADE_V = {"2.5", "4.5", "6.5", "9.0"};

    private static String defaultLanguage(Context context) {
        Locale system = context.getResources().getConfiguration().getLocales().get(0);
        return "pt".equalsIgnoreCase(system.getLanguage()) ? "pt-BR" : "en";
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences languagePrefs = newBase.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        String tag = languagePrefs.getString("language", defaultLanguage(newBase));
        Configuration config = new Configuration(newBase.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(tag));
        super.attachBaseContext(newBase.createConfigurationContext(config));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        buildUi();
        refreshFolder();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFolder();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(LinearLayout parent, String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(230, 225, 210));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(6), 0, dp(2));
        parent.addView(t);
        return t;
    }

    private Spinner spinner(LinearLayout parent, String key, String label, String[] items, int def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(16);
        t.setTextColor(Color.rgb(230, 225, 210));
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(Math.min(prefs.getInt(key, def), items.length - 1));
        row.addView(s, new LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(row);
        spinners.put(key, s);
        return s;
    }

    private CheckBox check(LinearLayout parent, String key, String label, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextSize(16);
        c.setTextColor(Color.rgb(230, 225, 210));
        c.setChecked(prefs.getBoolean(key, def));
        parent.addView(c);
        checks.put(key, c);
        return c;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(24, 22, 20));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(16), dp(24), dp(24));
        sv.addView(root);

        text(root, getString(R.string.language), 16, true);
        Spinner language = new Spinner(this);
        String[] languageNames = getResources().getStringArray(R.array.language_names);
        ArrayAdapter<String> languageAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, languageNames);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        language.setAdapter(languageAdapter);
        String selectedLanguage = prefs.getString("language", defaultLanguage(this));
        language.setSelection("pt-BR".equalsIgnoreCase(selectedLanguage) ? 1 : 0);
        root.addView(language, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        language.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (first) { first = false; return; }
                String next = position == 1 ? "pt-BR" : "en";
                if (!next.equalsIgnoreCase(prefs.getString("language", defaultLanguage(LauncherActivity.this)))) {
                    prefs.edit().putString("language", next).apply();
                    recreate();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        text(root, "Skyrim", 28, true);

        text(root, getString(R.string.game_folder), 20, true);
        folderText = text(root, "", 14, false);
        folderStatus = text(root, "", 14, false);
        Button pick = new Button(this);
        pick.setText(R.string.choose_skyrim_folder);
        pick.setOnClickListener(v -> {
            /* the game reads the folder with plain file calls: ask for file
             * access first (only shown once), then pick */
            if (hasStorage()) openPicker();
            else { pendingPick = true; requestStorage(); }
        });
        root.addView(pick);

        text(root, getString(R.string.options), 20, true);
        Spinner preset = spinner(root, "preset", getString(R.string.detail),
                getResources().getStringArray(R.array.detail_presets), 0);
        Spinner aspect = spinner(root, "aspect", getString(R.string.aspect_ratio),
                getResources().getStringArray(R.array.aspect_ratios), 0);
        int[][] m = modes(aspect.getSelectedItemPosition());
        Spinner res = spinner(root, "res", getString(R.string.resolution), modeNames(m), defaultMode(m));
        aspect.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (first) { first = false; return; }
                // keep the same height where the new list has it
                int oldH = modes(prefs.getInt("aspectShown", 0))[Math.max(0, res.getSelectedItemPosition())][1];
                int[][] nm = modes(pos);
                ArrayAdapter<String> a = new ArrayAdapter<>(LauncherActivity.this,
                        android.R.layout.simple_spinner_item, modeNames(nm));
                a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                res.setAdapter(a);
                int pick = defaultMode(nm);
                for (int i = 0; i < nm.length; i++) if (nm[i][1] == oldH) pick = i;
                res.setSelection(pick);
                prefs.edit().putInt("aspectShown", pos).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        prefs.edit().putInt("aspectShown", aspect.getSelectedItemPosition()).apply();
        spinner(root, "aa", getString(R.string.antialiasing), getResources().getStringArray(R.array.antialiasing), 0);
        spinner(root, "aniso", getString(R.string.anisotropic_filtering), getResources().getStringArray(R.array.anisotropic_filtering), 0);
        spinner(root, "texq", getString(R.string.texture_quality), getResources().getStringArray(R.array.texture_quality), 1);
        spinner(root, "shadow", getString(R.string.shadow_detail), getResources().getStringArray(R.array.shadow_detail), 0);
        spinner(root, "decals", getString(R.string.decal_quantity), getResources().getStringArray(R.array.decal_quantity), 0);
        spinner(root, "blur", getString(R.string.radial_blur_quality), getResources().getStringArray(R.array.radial_blur_quality), 0);
        spinner(root, "fade", getString(R.string.object_fade_distance), getResources().getStringArray(R.array.object_fade_distance), 0);
        check(root, "fxaa", getString(R.string.fxaa), false);
        check(root, "vsync", getString(R.string.vertical_sync), true);
        check(root, "touch", getString(R.string.touch_controls), true);
        check(root, "nintendo", getString(R.string.nintendo_button_layout), false);
        preset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (first) { first = false; return; }  // initial selection is not a user change
                applyPreset(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        playButton = new Button(this);
        playButton.setText(R.string.play);
        playButton.setTextSize(22);
        playButton.setOnClickListener(v -> play());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        lp.topMargin = dp(16);
        root.addView(playButton, lp);
        setContentView(sv);
    }

    /* Choosing a preset sets the detail options to what the PC launcher's preset does. */
    private void applyPreset(int p) {
        int[] tex = {1, 1, 2, 2}, shadow = {0, 1, 2, 3}, decals = {0, 1, 2, 3}, blur = {0, 1, 2, 2},
              fade = {0, 1, 2, 3}, aa = {0, 0, 2, 3}, aniso = {0, 2, 3, 4};
        boolean[] fxaa = {false, false, false, false};
        // texture index: 0 low,1 medium,2 high (TEXQ order)
        spinners.get("texq").setSelection(tex[p]);
        spinners.get("shadow").setSelection(shadow[p]);
        spinners.get("decals").setSelection(decals[p]);
        spinners.get("blur").setSelection(blur[p]);
        spinners.get("fade").setSelection(fade[p]);
        spinners.get("aa").setSelection(aa[p]);
        spinners.get("aniso").setSelection(aniso[p]);
        checks.get("fxaa").setChecked(fxaa[p]);
    }

    /* Storage: the app targets API 29 with legacy storage, so the normal
     * "allow access to files" prompt gives the game plain file access. Only
     * if this Android ignores legacy storage and the folder still can't be
     * read is all-files access asked for (once, automatically). */
    private boolean hasStorage() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                || (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager());
    }

    private void requestStorage() {
        requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_PERM) return;
        boolean ok = hasStorage();
        if (ok && pendingPick) openPicker();
        if (ok && pendingPlay) play();
        pendingPick = pendingPlay = false;
        refreshFolder();
    }

    /* folder exists but its files can't be read: legacy storage was refused */
    private boolean unreadable(List<String> bad) {
        String prefix = getString(R.string.unreadable_prefix);
        for (String b : bad) if (b.startsWith(prefix)) return true;
        return false;
    }

    private boolean hasAllFiles() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private void requestAllFiles() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    private void openPicker() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FOLDER || res != RESULT_OK || data == null || data.getData() == null) return;
        String path = treeToPath(data.getData());
        if (path == null) {
            folderStatus.setText(R.string.invalid_folder_location);
            return;
        }
        prefs.edit().putString("folder", path).apply();
        refreshFolder();
        if (!askedAllFiles && !hasAllFiles() && unreadable(checkFolder(path))) {
            askedAllFiles = true;
            requestAllFiles();
        }
    }

    /* content://...tree/primary:Games/Skyrim -> /storage/emulated/0/Games/Skyrim */
    private static String treeToPath(Uri tree) {
        String id;
        try { id = DocumentsContract.getTreeDocumentId(tree); } catch (Exception e) { return null; }
        int c = id.indexOf(':');
        if (c < 0) return null;
        String vol = id.substring(0, c), rel = id.substring(c + 1);
        String base = vol.equalsIgnoreCase("primary") ? Environment.getExternalStorageDirectory().getPath()
                : "/storage/" + vol;
        return rel.isEmpty() ? base : base + "/" + rel;
    }

    private static File findCi(File dir, String name) {
        File f = new File(dir, name);
        if (f.exists()) return f;
        String[] l = dir.list();
        if (l != null) for (String s : l) if (s.equalsIgnoreCase(name)) return new File(dir, s);
        return null;
    }

    // SHA-1 of the Steam TESV.exe (1.9.32.0.8) the port was recompiled from:
    // other versions have different code at different addresses and would crash.
    private static final String SHA_TESV_STEAM = "f39aecebf6e332616c4c518617073f6e8250e0f8";

    private void checkSha(File f, String want, String name, List<String> bad) {
        String got = sha1(f);
        if (got == null) bad.add(getString(R.string.cannot_read_file, name));
        else if (!got.equals(want)) bad.add(getString(R.string.wrong_version, name,
                got.substring(0, 8), want.substring(0, 8)));
    }

    /* SHA-1 as hex, cached by path, size and modification time; null if unreadable. */
    private String sha1(File f) {
        String key = "sha1:" + f.getAbsolutePath(), stamp = f.length() + ":" + f.lastModified();
        String cached = prefs.getString(key, null);
        if (cached != null && cached.startsWith(stamp + "=")) return cached.substring(stamp.length() + 1);
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            String hex = sb.toString();
            prefs.edit().putString(key, stamp + "=" + hex).apply();
            return hex;
        } catch (Exception e) {
            return null;
        }
    }

    /* Returns the list of problems with the folder (empty when it is ready). */
    private List<String> checkFolder(String path) {
        List<String> bad = new ArrayList<>();
        if (path == null) { bad.add(getString(R.string.no_folder_chosen)); return bad; }
        File d = new File(path);
        if (!d.isDirectory()) { bad.add(getString(R.string.folder_not_found)); return bad; }
        File data = findCi(d, "Data");
        File esm = data != null ? findCi(data, "Skyrim.esm") : null;
        if (data == null) bad.add(getString(R.string.data_folder_missing));
        else if (esm == null) bad.add(data.canRead() ? getString(R.string.skyrim_esm_missing)
                : getString(R.string.cannot_read_data_folder));
        else if (!esm.canRead()) bad.add(getString(R.string.cannot_read_skyrim_esm));
        // Only the original exe is checked: Steamless output differs between Steamless versions.
        File packed = findCi(d, "TESV.exe");
        if (packed == null) bad.add(getString(R.string.game_executable_missing));
        else checkSha(packed, SHA_TESV_STEAM, "TESV.exe", bad);
        for (String n : new String[] {"d3dx9_42.dll", "X3DAudio1_7.dll", "binkw32.dll"})
            if (findCi(d, n) == null) bad.add(getString(R.string.file_missing, n));
        return bad;
    }

    private void refreshFolder() {
        String path = prefs.getString("folder", null);
        folderText.setText(path == null ? getString(R.string.no_folder_instructions) : path);
        List<String> bad = checkFolder(path);
        folderStatus.setText(bad.isEmpty() ? getString(R.string.ready)
                : getString(R.string.not_ready, String.join(", ", bad)));
        folderStatus.setTextColor(bad.isEmpty() ? Color.rgb(140, 200, 120) : Color.rgb(230, 150, 110));
        playButton.setEnabled(bad.isEmpty());
    }

    private void saveChoices() {
        SharedPreferences.Editor e = prefs.edit();
        for (Map.Entry<String, Spinner> s : spinners.entrySet()) e.putInt(s.getKey(), s.getValue().getSelectedItemPosition());
        for (Map.Entry<String, CheckBox> c : checks.entrySet()) e.putBoolean(c.getKey(), c.getValue().isChecked());
        e.apply();
    }

    private int sel(String k) { return spinners.get(k).getSelectedItemPosition(); }

    private void play() {
        if (!hasStorage()) { pendingPlay = true; requestStorage(); return; }
        saveChoices();
        String folder = prefs.getString("folder", null);
        File save = new File(folder, "port/save");
        File iniDir = new File(save, "Documents/My Games/Skyrim");
        iniDir.mkdirs();
        try {
            writePrefs(new File(folder), new File(iniDir, "SkyrimPrefs.ini"));
            // the native side builds the same mode list from this aspect (see modes())
            int[] av = ASPECT_V[sel("aspect")];
            try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(save, "display.cfg")))) {
                w.write(av[0] + " " + av[1] + "\n");
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(save, "touch.cfg")))) {
                w.write((checks.get("touch").isChecked() ? "1" : "0") + " " + (checks.get("nintendo").isChecked() ? "1" : "0") + "\n");
            }
        } catch (Exception ex) {
            folderStatus.setText(getString(R.string.settings_write_failed, ex.toString()));
            return;
        }
        Intent i = new Intent(this, SkyrimActivity.class);
        i.putExtra("game", folder);
        startActivity(i);
        finish();
    }

    /* SkyrimPrefs.ini: keep what is there, start from the chosen preset file
     * in the game folder, then apply the individual options. */
    private void writePrefs(File game, File out) throws Exception {
        LinkedHashMap<String, LinkedHashMap<String, String>> ini = new LinkedHashMap<>();
        readIni(out, ini);
        File preset = findCi(game, PRESET_FILES[sel("preset")]);
        if (preset != null) readIni(preset, ini);
        int[] mode = modes(sel("aspect"))[sel("res")];
        put(ini, "Display", "iSize W", Integer.toString(mode[0]));
        put(ini, "Display", "iSize H", Integer.toString(mode[1]));
        put(ini, "Display", "bFull Screen", "1");
        put(ini, "Display", "iMultiSample", Integer.toString(AA_V[sel("aa")]));
        put(ini, "Display", "iMaxAnisotropy", Integer.toString(ANISO_V[sel("aniso")]));
        put(ini, "Display", "iTexMipMapSkip", Integer.toString(TEXQ_V[sel("texq")]));
        int sh = SHADOW_V[sel("shadow")];
        put(ini, "Display", "iShadowMapResolution", Integer.toString(sh));
        put(ini, "Display", "iShadowMapResolutionPrimary", Integer.toString(sh));
        put(ini, "Display", "iShadowMapResolutionSecondary", Integer.toString(Math.max(512, sh / 2)));
        int dec = DECALS_V[sel("decals")];
        put(ini, "Decals", "bDecals", dec > 0 ? "1" : "0");
        put(ini, "Decals", "bSkinnedDecals", dec > 0 ? "1" : "0");
        put(ini, "Decals", "uMaxDecals", Integer.toString(dec));
        put(ini, "Decals", "uMaxSkinDecals", Integer.toString(dec / 10));
        put(ini, "Display", "iMaxDecalsPerFrame", Integer.toString(dec / 10));
        put(ini, "Display", "iMaxSkinDecalsPerFrame", Integer.toString(dec / 40));
        put(ini, "Imagespace", "iRadialBlurLevel", Integer.toString(sel("blur")));
        String f = FADE_V[sel("fade")];
        put(ini, "LOD", "fLODFadeOutMultObjects", f);
        put(ini, "LOD", "fLODFadeOutMultItems", f);
        put(ini, "LOD", "fLODFadeOutMultActors", f);
        put(ini, "Display", "bFXAAEnabled", checks.get("fxaa").isChecked() ? "1" : "0");
        put(ini, "Display", "iPresentInterval", checks.get("vsync").isChecked() ? "1" : "0");
        put(ini, "MAIN", "bGamepadEnable", "1");
        put(ini, "Controls", "bGamePadRumble", "1");
        try (Writer wr = new OutputStreamWriter(new FileOutputStream(out))) {
            for (Map.Entry<String, LinkedHashMap<String, String>> s : ini.entrySet()) {
                wr.write("[" + s.getKey() + "]\r\n");
                for (Map.Entry<String, String> kv : s.getValue().entrySet()) wr.write(kv.getKey() + "=" + kv.getValue() + "\r\n");
                wr.write("\r\n");
            }
        }
    }

    private static void put(LinkedHashMap<String, LinkedHashMap<String, String>> ini, String sec, String k, String v) {
        String sk = sec;
        for (String s : ini.keySet()) if (s.equalsIgnoreCase(sec)) sk = s;
        LinkedHashMap<String, String> m = ini.get(sk);
        if (m == null) ini.put(sk, m = new LinkedHashMap<>());
        String kk = k;
        for (String s : m.keySet()) if (s.equalsIgnoreCase(k)) kk = s;
        m.put(kk, v);
    }

    private static void readIni(File f, LinkedHashMap<String, LinkedHashMap<String, String>> ini) {
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)))) {
            String sec = "General", line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue;
                if (line.startsWith("[")) {
                    int e = line.indexOf(']');
                    sec = (e > 0 ? line.substring(1, e) : line.substring(1)).trim();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0) put(ini, sec, line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (Exception ignored) {
        }
    }
}
