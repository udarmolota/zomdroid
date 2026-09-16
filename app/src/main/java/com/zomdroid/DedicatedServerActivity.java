package com.zomdroid;

import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.zomdroid.game.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Dashboard stays in ART's main process; the server JVM owns :server. */
public final class DedicatedServerActivity extends AppCompatActivity {
    private GameInstance instance;
    private DedicatedServerProfile profile;
    private LinearLayout form;
    private AutoCompleteTextView name;
    private EditText heap, port, players, password, admin, interval, mods, map;
    private CheckBox upnp;
    private TextView status, console, telemetry, profileState;
    private int normalStatusColor;
    private NestedScrollView consoleScroll;
    private ImageButton profilePick;
    private ArrayAdapter<String> profileNames;
    private boolean confirmedNewWorld;
    private Button start, save, stop, force, load, advanced;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Messenger server;
    private boolean bound, resumed, starting;
    private String lastInternet = "";
    private CoopInternetView internet;
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what == DedicatedServerService.SNAPSHOT) render(message.getData());
        return true;
    }));
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder binder) { server = new Messenger(binder); query(); }
        @Override public void onServiceDisconnected(ComponentName n) { server = null; starting = false; }
        @Override public void onBindingDied(ComponentName n) { detach(); starting = false; }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AppStorage.init(this); LauncherPreferences.init(this); GameInstanceManager.init(this);
        instance = GameInstanceManager.requireSingleton().getInstanceByName(getIntent().getStringExtra(GameActivity.EXTRA_GAME_INSTANCE_NAME));
        if (instance == null) { finish(); return; }
        setTitle(R.string.ds_title);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density); page.setPadding(pad, pad, pad, pad);
        NestedScrollView scroll = new NestedScrollView(this); scroll.setFillViewport(true); scroll.addView(page); setContentView(scroll);
        // Edge-to-edge target SDK: keep the dashboard clear of status/navigation bars and keyboard.
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); return insets;
        });
        TextView description = new TextView(this); description.setText(R.string.ds_description); page.addView(description);
        status = new TextView(this); status.setText(R.string.ds_idle); status.setTextSize(18);
        normalStatusColor = status.getCurrentTextColor(); page.addView(status);
        telemetry = new TextView(this); page.addView(telemetry);
        form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); page.addView(form);
        // Profile name with a dropdown of the existing Host profiles (coop-probe/Server/*.ini).
        // Picking one loads it at once, and the state line below tells a loaded profile from a
        // name that would silently create a new world.
        LinearLayout nameRow = new LinearLayout(this); nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(android.view.Gravity.CENTER_VERTICAL); form.addView(nameRow);
        name = new AutoCompleteTextView(this); name.setSingleLine(true); name.setHint(R.string.ds_profile);
        nameRow.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        profilePick = new ImageButton(this); profilePick.setImageResource(R.drawable.mt_icon_expand_more);
        profilePick.setBackground(null); profilePick.setContentDescription(getString(R.string.ds_profiles_show));
        // The whole list, not only the names matching what is typed.
        profilePick.setOnClickListener(v -> { refreshProfiles(); profileNames.getFilter().filter(null, count -> name.showDropDown()); });
        nameRow.addView(profilePick);
        name.setText(getPreferences(MODE_PRIVATE).getString(instance.getName(), "zomdroid"));
        profileNames = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
        name.setAdapter(profileNames); name.setThreshold(1);
        name.setOnClickListener(v -> name.showDropDown());
        name.setOnItemClickListener((parent, v, position, id) -> loadProfile());
        refreshProfiles();
        load = button(form, R.string.ds_load, () -> loadProfile());
        profileState = new TextView(this); form.addView(profileState);
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) {
                if (profile != null && !profile.name.equals(text.toString().trim())) profileState.setText(R.string.ds_profile_changed);
            }
        });
        heap = field(R.string.ds_heap, true, false); port = field(R.string.ds_port, true, false);
        players = field(R.string.ds_players, true, false); interval = field(R.string.ds_interval, true, false);
        password = field(R.string.ds_password, false, true); admin = field(R.string.ds_admin, false, true);
        mods = field(R.string.ds_mods, false, false); map = field(R.string.ds_map, false, false);
        upnp = new CheckBox(this); upnp.setText("UPnP"); form.addView(upnp);
        advanced = button(form, R.string.ds_advanced, this::editConfiguration);
        start = button(page, R.string.ds_start, this::startServer);
        save = button(page, R.string.ds_save, () -> send(DedicatedServerService.SAVE));
        stop = button(page, R.string.ds_stop, () -> new AlertDialog.Builder(this)
                .setMessage(R.string.ds_stop_confirm).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ds_stop, (d, w) -> send(DedicatedServerService.STOP)).show());
        force = button(page, R.string.ds_force, () -> new AlertDialog.Builder(this)
                .setMessage(R.string.ds_force_confirm).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ds_force, (d, w) -> send(DedicatedServerService.FORCE)).show());
        force.setVisibility(android.view.View.GONE);
        // Server log in a fixed window like the Steam download console, only taller. It follows new
        // lines unless the player has scrolled up to read.
        float density = getResources().getDisplayMetrics().density;
        MaterialCardView logCard = new MaterialCardView(this);
        logCard.setRadius(12 * density); logCard.setStrokeWidth(0);
        logCard.setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFFEEEEEE));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int)(320 * density));
        cardParams.topMargin = pad / 2; page.addView(logCard, cardParams);
        consoleScroll = new NestedScrollView(this); logCard.addView(consoleScroll);
        console = new TextView(this); console.setTextSize(11); console.setTextIsSelectable(true);
        console.setTypeface(android.graphics.Typeface.MONOSPACE);
        console.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF444444));
        int inner = pad * 3 / 4; console.setPadding(inner, inner, inner, inner);
        consoleScroll.addView(console);
        internet = new CoopInternetView(this);
        loadProfile();
    }

    private EditText field(int label, boolean number, boolean secret) {
        TextView title = new TextView(this); title.setText(label); form.addView(title);
        EditText edit = new EditText(this); edit.setSingleLine(true);
        edit.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT |
                (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        form.addView(edit); return edit;
    }
    // MaterialButton, not Button: a framework Button created in code ignores the app theme and
    // comes out grey; this one takes the launcher's filled orange.
    private Button button(LinearLayout parent, int text, Runnable action) {
        MaterialButton button = new MaterialButton(this); button.setText(text); button.setOnClickListener(v -> action.run()); parent.addView(button); return button;
    }
    /** Existing profiles: every Server/*.ini in the hosting profile, Host-created ones included. */
    private void refreshProfiles() {
        String[] existing = new File(instance.getHomePath(), "coop-probe/Server").list((dir, file) -> file.endsWith(".ini"));
        ArrayList<String> names = new ArrayList<>();
        if (existing != null) for (String file : existing) names.add(file.substring(0, file.length() - 4));
        Collections.sort(names);
        profileNames.clear(); profileNames.addAll(names);
    }
    /** A loaded profile with or without its world, or a name that will create a new world. */
    private void showProfileState() {
        boolean known = profile.config(".ini").exists();
        boolean world = new File(profile.root, "Saves/Multiplayer/" + profile.name).isDirectory();
        profileState.setText(!known ? getString(R.string.ds_profile_new, profile.name)
                : getString(world ? R.string.ds_profile_loaded : R.string.ds_profile_loaded_no_world, profile.name));
    }
    private void loadProfile() {
        try {
            profile = new DedicatedServerProfile(instance, name.getText().toString().trim()); profile.load();
            Properties p = profile.options();
            heap.setText(Integer.toString(profile.heapMb)); port.setText(p.getProperty("DefaultPort", "16261"));
            players.setText(p.getProperty("MaxPlayers", "4")); interval.setText(p.getProperty("SaveWorldEveryMinutes", "10"));
            password.setText(p.getProperty("Password", "")); admin.setText(profile.adminPassword);
            mods.setText(p.getProperty("Mods", "")); map.setText(p.getProperty("Map", "Muldraugh, KY"));
            upnp.setChecked(Boolean.parseBoolean(p.getProperty("UPnP", "true")));
            showProfileState();
        } catch (Exception e) { profileState.setText(""); showError(e); }
    }
    private static int number(EditText edit, int min, int max) {
        int n = Integer.parseInt(edit.getText().toString());
        if (n < min || n > max) throw new IllegalArgumentException(min + "–" + max); return n;
    }
    private void persist() throws Exception {
        if (DedicatedServerService.active(this)) throw new IOException(getString(R.string.ds_busy));
        if (profile == null || !profile.name.equals(name.getText().toString().trim()))
            throw new IOException(getString(R.string.ds_load_first));
        profile.heapMb = number(heap, 512, 16384); profile.adminPassword = admin.getText().toString();
        Map<String, String> options = new LinkedHashMap<>();
        options.put("DefaultPort", Integer.toString(number(port, 1024, 65534)));
        options.put("MaxPlayers", Integer.toString(number(players, 1, 100)));
        options.put("SaveWorldEveryMinutes", Integer.toString(number(interval, 1, 120)));
        options.put("Password", password.getText().toString()); options.put("Mods", mods.getText().toString());
        options.put("Map", map.getText().toString()); options.put("UPnP", Boolean.toString(upnp.isChecked()));
        for (String value : options.values()) if (value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("Invalid line break");
        if (!profile.config(".ini").exists()) { options.put("Public", "false"); options.put("PauseEmpty", "true"); }
        profile.savePreferences(); profile.setOptions(options);
        getPreferences(MODE_PRIVATE).edit().putString(instance.getName(), profile.name).apply();
    }
    private void startServer() {
        try {
            if (InstallerService.isTaskRunning()) throw new IOException(getString(R.string.ds_installing));
            if (GameActivity.hasGameJvm()) throw new IOException(getString(R.string.ds_close_game));
            String build = instance.getBuildVersion();
            if (build != null && build.startsWith("41") && !com.zomdroid.steam.MultiplayerLibraries.hasFiles(new File(instance.getGamePath())))
                throw new IOException(getString(R.string.ds_need_libraries));
            if (profile != null && profile.name.equals(name.getText().toString().trim())
                    && !profile.config(".ini").exists() && !confirmedNewWorld) {
                new AlertDialog.Builder(this).setMessage(getString(R.string.ds_new_world_confirm, profile.name))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.ds_new_world_create, (d, w) -> { confirmedNewWorld = true; startServer(); })
                        .show();
                return;
            }
            confirmedNewWorld = false;
            persist();
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 10);
            starting = true; start.setEnabled(false);
            startForegroundService(new Intent(this, DedicatedServerService.class).putExtra("instance", instance.getName()).putExtra("profile", profile.name));
            handler.postDelayed(() -> { starting = false; }, 10000);
        } catch (Exception e) { starting = false; showError(e); }
    }
    private void editConfiguration() {
        if (DedicatedServerService.active(this)) return;
        String[] suffixes = {".ini", "_SandboxVars.lua", "_spawnregions.lua"};
        new AlertDialog.Builder(this).setTitle(R.string.ds_advanced).setItems(suffixes, (dialog, which) -> {
            try {
                if (profile == null || !profile.name.equals(name.getText().toString().trim())) throw new IOException(getString(R.string.ds_load_first));
                DedicatedServerProfile editing = profile;
                EditText text = new EditText(this); text.setText(editing.read(suffixes[which]));
                text.setGravity(android.view.Gravity.TOP); text.setMinLines(8); text.setTextSize(12);
                ScrollView scroll = new ScrollView(this); scroll.addView(text);
                new AlertDialog.Builder(this).setTitle(editing.name + suffixes[which]).setView(scroll)
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.ds_save_config, (d, w) -> {
                            try {
                                if (DedicatedServerService.active(this)) throw new IOException(getString(R.string.ds_busy));
                                DedicatedServerProfile.write(editing.config(suffixes[which]), text.getText().toString().getBytes(StandardCharsets.UTF_8));
                                loadProfile();
                            } catch (Exception e) { showError(e); }
                        }).show();
            } catch (Exception e) { showError(e); }
        }).show();
    }

    private void send(int action) {
        if (server == null) return;
        try { Message msg = Message.obtain(null, action); msg.replyTo = replies; server.send(msg); }
        catch (RemoteException e) { server = null; }
    }
    private void query() { send(DedicatedServerService.QUERY); }
    private void render(Bundle b) {
        String phase = b.getString("state", "starting");
        int label = phase.equals("running") ? R.string.ds_running : phase.equals("stopping") ? R.string.ds_stopping : phase.equals("failed") ? R.string.ds_failed : R.string.ds_starting;
        status.setText(getString(label) + " · " + b.getString("instance", "") + " / " + b.getString("profile", ""));
        status.setTextColor(phase.equals("failed") ? MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorError, 0xFFB00020) : normalStatusColor);
        long seconds = b.getLong("uptime");
        status.append("\n" + getString(R.string.ds_uptime, String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)));
        if (b.getBoolean("saving")) status.append("\n" + getString(R.string.ds_saving));
        if (!b.getString("error", "").isEmpty()) status.append("\n" + b.getString("error"));
        boolean same = instance.getName().equals(b.getString("instance"));
        save.setEnabled(same && phase.equals("running") && !b.getBoolean("saving"));
        stop.setEnabled(same && (phase.equals("running") || phase.equals("starting")));
        force.setVisibility(same && b.getBoolean("canForce") ? android.view.View.VISIBLE : android.view.View.GONE);
    }
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean active = DedicatedServerService.active(DedicatedServerActivity.this);
            for (int i = 0; i < form.getChildCount(); i++) form.getChildAt(i).setEnabled(!active && !starting);
            name.setEnabled(!active && !starting); profilePick.setEnabled(!active && !starting);
            start.setEnabled(!active && !starting);
            if (active) {
                if (!bound) bound = bindService(new Intent(DedicatedServerActivity.this, DedicatedServerService.class), connection, 0);
                query();
            } else {
                detach(); save.setEnabled(false); stop.setEnabled(false);
                force.setVisibility(android.view.View.GONE);
                if (!starting) {
                    File lifecycleFile = new File(instance.getHomePath(), "coop-probe/dedicated-state");
                    String lifecycle = readTail(lifecycleFile, 512);
                    // The service process can disappear before its final two-second log poll. Also
                    // recover from the logger/shutdown-hook race by trusting PZ's definitive marker.
                    if (!lifecycle.equals("clean") && readTail(new File(instance.getGamePath(),
                            "server-native.log"), 65536).contains("Shutdown handling finished")) {
                        lifecycle = "clean";
                        try { DedicatedServerProfile.write(lifecycleFile,
                                lifecycle.getBytes(StandardCharsets.UTF_8)); }
                        catch (IOException ignored) { }
                    }
                    status.setText(lifecycle.equals("clean") ? R.string.ds_stopped : lifecycle.isEmpty() ? R.string.ds_idle : R.string.ds_unconfirmed);
                    status.setTextColor(!lifecycle.isEmpty() && !lifecycle.equals("clean")
                            ? MaterialColors.getColor(DedicatedServerActivity.this,
                                    com.google.android.material.R.attr.colorError, 0xFFB00020)
                            : normalStatusColor);
                    String error = readTail(new File(instance.getHomePath(), "coop-probe/dedicated-error.txt"), 2000);
                    if (!error.isEmpty()) status.append("\n" + error);
                }
                internet.close(); lastInternet = "";
            }
            String log = readTail(new File(instance.getGamePath(), "server-native.log"), 16000);
            if (!log.contentEquals(console.getText())) {
                boolean follow = !consoleScroll.canScrollVertically(1);
                console.setText(log);
                if (follow) consoleScroll.post(() -> consoleScroll.scrollTo(0, console.getHeight()));
            }
            if (active) {
                Properties p = readProperties(new File(instance.getHomePath(), "coop-probe/dedicated-telemetry.properties"));
                telemetry.setText(getString(R.string.ds_metrics, p.getProperty("players", "?"), p.getProperty("heapUsedMb", "?"), p.getProperty("heapMaxMb", "?")));
                Properties net = readProperties(new File(instance.getHomePath(), "coop-probe/hosting-internet.properties"));
                String key = net.toString(); if (!key.equals(lastInternet)) { lastInternet = key; internet.update(net); }
            } else telemetry.setText("");
            if (resumed) handler.postDelayed(this, 1500);
        }
    };
    static String readTail(File file, int limit) {
        if (!file.isFile()) return "";
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            int n = (int)Math.min(limit, f.length()); f.seek(f.length() - n); byte[] bytes = new byte[n]; f.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) { return e.toString(); }
    }
    static Properties readProperties(File file) {
        Properties p = new Properties(); try (InputStream in = new FileInputStream(file)) { p.load(in); } catch (IOException ignored) { } return p;
    }
    private void detach() { if (bound) { unbindService(connection); bound = false; } server = null; }
    private void showError(Exception e) { new AlertDialog.Builder(this).setTitle(R.string.ds_failed).setMessage(e.getMessage()).setPositiveButton(android.R.string.ok, null).show(); }
    @Override protected void onResume() { super.onResume(); resumed = true; if (profileNames != null) refreshProfiles(); handler.post(refresh); }
    @Override protected void onPause() { resumed = false; handler.removeCallbacks(refresh); detach(); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); if (internet != null) internet.close(); super.onDestroy(); }
}
