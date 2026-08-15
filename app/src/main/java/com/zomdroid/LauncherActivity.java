package com.zomdroid;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Html;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import androidx.core.text.HtmlCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zomdroid.databinding.ActivityLauncherBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;
import com.zomdroid.input.AbstractControlElement;
import com.zomdroid.input.ControlElementDescription;
import com.zomdroid.input.GamepadManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class LauncherActivity extends AppCompatActivity {
    private static final String LOG_TAG = LauncherActivity.class.getName();
    ActivityLauncherBinding binding;
    private NavController navController;
    private AppBarConfiguration appBarConfiguration;
    private boolean inited = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);

        super.onCreate(savedInstanceState);

        binding = ActivityLauncherBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // >>> ensure custom mapping is loaded at app start <<<
        GamepadManager.loadCustomMapping(this);

        binding.appbarLayout.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @NonNull
            @Override
            public WindowInsets onApplyWindowInsets(@NonNull View v, @NonNull WindowInsets windowInsets) {
                Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(
                        v.getPaddingLeft(),
                        insets.top,
                        v.getPaddingRight(),
                        //v.getPaddingBottom()
                        insets.bottom
                );

                return windowInsets;
            }
        });

        // Pad the drawer's bottom by the system bar height so the bottom icon row + line aren't
        // hidden behind the navigation bar / gesture area.
        binding.drawerContainer.setOnApplyWindowInsetsListener((v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), insets.bottom);
            return windowInsets;
        });

        setSupportActionBar(binding.appbar);

        appBarConfiguration = new AppBarConfiguration.Builder(
                R.id.launcher_fragment)
                .setOpenableLayout(binding.drawerLayout)
                .build();

        navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
        NavigationUI.setupWithNavController(binding.launcherNv, navController);

        binding.launcherNv.setNavigationItemSelectedListener(item -> {
            if (item.getItemId() == R.id.action_game_settings) {
                binding.drawerLayout.close();
                navController.navigate(R.id.action_game_settings);
                return true;
            } else if (item.getItemId() == R.id.action_open_controls_editor) {
                //Intent intent = new Intent(this, ControlsEditorActivity.class);
                //startActivity(intent);
                binding.drawerLayout.close();
                navController.navigate(R.id.action_open_controls_editor_launch);
                return true;
            } else if (item.getItemId() == R.id.action_open_gamepad_mapper) {
                // Navigate to gamepad mapper using NavController
                binding.drawerLayout.close();
                navController.navigate(R.id.action_open_gamepad_mapper);
                return true;
            } else if (item.getItemId() == R.id.action_open_install_mod) {
                // Navigate to Mod installation
                binding.drawerLayout.close();
                navController.navigate(R.id.action_open_install_mod);
                return true;
            } else if (item.getItemId() == R.id.action_install_controls) {
                binding.drawerLayout.close();
                navController.navigate(R.id.action_install_controls);
                return true;
            } else if (item.getItemId() == R.id.action_open_optimization) {
                binding.drawerLayout.close();
                navController.navigate(R.id.action_open_optimization);
                return true;
            } else if (item.getItemId() == R.id.action_install_native_libs) {
                binding.drawerLayout.close();
                navController.navigate(R.id.action_install_native_libs);
                return true;
            } else if (item.getItemId() == R.id.action_download_steam) {
                binding.drawerLayout.close();
                navController.navigate(R.id.action_download_steam);
                return true;
            } else if (item.getItemId() == R.id.action_bug_report) {
                binding.drawerLayout.close();
                sendBugReport();
                return true;
        }

        binding.drawerLayout.close();

            return NavigationUI.onNavDestinationSelected(item, navController)
                    || super.onOptionsItemSelected(item);
        });

        // Bottom icon row (Wiki / Donate / Reddit / Version) — icons only, pinned to drawer bottom.
        binding.navBottomWiki.setOnClickListener(v -> {
            binding.drawerLayout.close();
            navController.navigate(R.id.action_open_wiki_fragment);
        });
        binding.navBottomDonate.setOnClickListener(v -> showDonateDialog());
        binding.navBottomReddit.setOnClickListener(v -> showRedditDialog());
        binding.navBottomVersion.setOnClickListener(v -> checkForUpdate());
        binding.navBottomRimdroid.setOnClickListener(v -> showRimDroidDialog());

        // Silent, at-most-once-a-day check so the GitHub icon can badge a newer release without
        // the user having to tap it. A manual tap still runs checkForUpdate() and shows a dialog.
        maybeDailyUpdateCheck();
    }

    private void showDonateDialog() {
        final SpannableString s = new SpannableString(getString(R.string.donate_message));
        Linkify.addLinks(s, Linkify.WEB_URLS);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_title_donate)
                .setMessage(s)
                .setPositiveButton(getString(R.string.dialog_button_ok), null)
                .create();
        dialog.show();
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) messageView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    /** Same shape as the Reddit and Support dialogs: a sentence and a tappable link, no browser jump. */
    private void showRimDroidDialog() {
        final SpannableString s = new SpannableString(getString(R.string.rimdroid_dialog_message));
        Linkify.addLinks(s, Linkify.WEB_URLS);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rimdroid_dialog_title)
                .setMessage(s)
                .setPositiveButton(getString(R.string.dialog_button_ok), null)
                .create();
        dialog.show();
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) messageView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private void showRedditDialog() {
        final SpannableString s = new SpannableString(getString(R.string.reddit_message));
        Linkify.addLinks(s, Linkify.WEB_URLS);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reddit_dialog_title)
                .setMessage(s)
                .setPositiveButton(getString(R.string.dialog_button_ok), null)
                .create();
        dialog.show();
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) messageView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    @Override
    public boolean onSupportNavigateUp() {
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    private void checkForUpdate() {
        new Thread(() -> {
            try {
                JSONObject json = fetchLatestRelease();
                String latestTag = json.getString("tag_name"); // "v1.4.1"
                String releaseUrl = json.getString("html_url");
                String latest = latestTag.startsWith("v") ? latestTag.substring(1) : latestTag;
                String current = BuildConfig.VERSION_NAME;

                // Keep the daily-check badge state in sync with whatever the manual check just saw.
                LauncherPreferences.requireSingleton().setLatestSeenTag(latestTag);

                runOnUiThread(() -> { showVersionDialog(current, latest, releaseUrl); refreshUpdateBadge(); });

            } catch (Exception e) {
                runOnUiThread(() -> showVersionDialog(BuildConfig.VERSION_NAME, null, null));
            }
        }).start();
    }

    private static JSONObject fetchLatestRelease() throws Exception {
        URL url = new URL("https://api.github.com/repos/udarmolota/zomdroid/releases/latest");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return new JSONObject(sb.toString());
    }

    // ---- Update badge (GitHub icon), checked at most once a day --------------

    /** True only if the seen GitHub tag is STRICTLY NEWER than the installed version. A plain
     *  "differs" check would falsely badge dev builds that run ahead of the public release. */
    private boolean updateAvailable() {
        String tag = LauncherPreferences.requireSingleton().getLatestSeenTag();
        if (tag == null || tag.trim().isEmpty()) return false;
        return compareVersions(tag.replaceFirst("^[vV]", ""), BuildConfig.VERSION_NAME) > 0;
    }

    /** Compare dotted version strings numerically ("1.4.10" > "1.4.3"). >0 if a is newer than b. */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-+ ]"), pb = b.split("[.\\-+ ]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    /**
     * Leading digits of a version component, ignoring any suffix: "7v5" -> 7, "8-rc1" -> 8, "" -> 0.
     *
     * Test builds are named with a suffix ("1.4.7v4", "1.4.7v5"). A strict parseInt threw on the
     * last component, the exception was swallowed as 0, and the whole build compared as 1.4.0 —
     * older than any published release. That is why every tester build showed the update badge
     * against the v1.4.7 tag regardless of its number, and why lowering 1.4.8 to 1.4.7v5 did not
     * help: the suffix, not the number, was the problem.
     */
    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        String t = s.trim();
        int end = 0;
        while (end < t.length() && Character.isDigit(t.charAt(end))) end++;
        if (end == 0) return 0;
        try { return Integer.parseInt(t.substring(0, end)); } catch (Exception e) { return 0; }
    }

    private void refreshUpdateBadge() {
        View dot = findViewById(R.id.update_badge);
        if (dot != null) dot.setVisibility(updateAvailable() ? View.VISIBLE : View.GONE);
    }

    /**
     * Once per day, on launcher start, ask GitHub for the latest release and remember it so the
     * drawer can badge the GitHub icon. The attempt DAY is recorded before the network call, so a
     * phone with no internet still tries at most once a day. The stored tag is compared against the
     * live installed version in {@link #updateAvailable()}, so the badge clears itself after an update.
     */
    private void maybeDailyUpdateCheck() {
        refreshUpdateBadge(); // reflect whatever we already know, every start
        LauncherPreferences lp = LauncherPreferences.requireSingleton();
        String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
        if (today.equals(lp.getUpdateCheckDay())) return; // already tried today
        lp.setUpdateCheckDay(today);                       // count the attempt now (even if it fails)

        new Thread(() -> {
            try {
                String tag = fetchLatestRelease().getString("tag_name");
                lp.setLatestSeenTag(tag);
                runOnUiThread(this::refreshUpdateBadge);
            } catch (Exception ignored) {
                // offline/failed — try again tomorrow
            }
        }, "zd-daily-update-check").start();
    }

    // ---- Bug report -------------------------------------------------------------

    /**
     * Open the user's email app pre-filled with a bug report — with the same log bundle that
     * "Export logs" produces attached, so a report arrives diagnosable. Building the zip can touch
     * multi-MB console.txt/native.log, so it runs off the UI thread; the email intent fires once
     * it's ready. If there's no instance (nothing to log) it falls back to a text-only mailto.
     */
    private void sendBugReport() {
        final java.util.Date now = new java.util.Date();
        final String date = new java.text.SimpleDateFormat("ddMMyyyy", java.util.Locale.US).format(now);
        // Distinct timestamp for the zip name (adds time-of-day) so a tester sending several
        // reports in one sitting doesn't overwrite the same "zomdroid_report.zip" attachment.
        final String timestamp = new java.text.SimpleDateFormat("ddMMyyyy_HHmm", java.util.Locale.US).format(now);
        final String device = "Device: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE
                + "\nZomdroid: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";

        java.util.List<GameInstance> instances = GameInstanceManager.requireSingleton().getInstances();
        GameInstance instance = instances.isEmpty() ? null : instances.get(0);
        if (instance == null) { startBugReportEmail(date, device, null); return; }

        Toast.makeText(this, R.string.bug_report_preparing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Uri attach = null;
            try {
                File dir = new File(getCacheDir(), "reports");
                if (dir.isDirectory() || dir.mkdirs()) {
                    File zip = new File(dir, "zomdroid_report_" + timestamp + ".zip");
                    try (OutputStream out = new FileOutputStream(zip)) {
                        InstallerService.writeLogReportZip(instance, out);
                    }
                    if (zip.length() > 0)
                        attach = androidx.core.content.FileProvider.getUriForFile(
                                this, "com.zomdroid.fileprovider", zip);
                }
            } catch (Throwable t) { attach = null; } // no logs → still send the text report
            final Uri fAttach = attach;
            runOnUiThread(() -> startBugReportEmail(date, device, fAttach));
        }).start();
    }

    private void startBugReportEmail(String date, String device, Uri attachment) {
        String[] to = { getString(R.string.bug_report_email) };
        String subject = getString(R.string.bug_report_subject, date);
        String body = getString(R.string.bug_report_body, device);
        Intent i;
        if (attachment != null) {
            // ACTION_SEND carries an attachment (mailto/SENDTO can't). A chooser lets the user pick
            // their mail app; to/subject/body/zip are all prefilled.
            i = new Intent(Intent.ACTION_SEND);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_EMAIL, to);
            i.putExtra(Intent.EXTRA_SUBJECT, subject);
            i.putExtra(Intent.EXTRA_TEXT, body);
            i.putExtra(Intent.EXTRA_STREAM, attachment);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(Intent.createChooser(i, getString(R.string.nav_bug_report))); return; }
            catch (android.content.ActivityNotFoundException ignored) { /* fall through to mailto */ }
        }
        // No attachment (or no app took the SEND) → plain mailto, text only.
        i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        i.putExtra(Intent.EXTRA_EMAIL, to);
        i.putExtra(Intent.EXTRA_SUBJECT, subject);
        i.putExtra(Intent.EXTRA_TEXT, body);
        try {
            startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.bug_report_no_mail, Toast.LENGTH_LONG).show();
        }
    }

    private void showVersionDialog(String current, String latest, String releaseUrl) {
        String message;
        if (latest == null) {
            message = getString(R.string.version_check_error, current);
        } else {
            // Numeric compare, not equals(): a dev build that runs ahead of the published release
            // differs from it but is not behind it, and telling the user to "update" to an older
            // release is worse than saying nothing. Same comparison the badge uses, so the dialog
            // and the badge can no longer contradict each other.
            int cmp = compareVersions(latest, current);
            if (cmp > 0) {
                message = getString(R.string.version_check_update_available, current, latest, releaseUrl);
            } else if (cmp == 0) {
                message = getString(R.string.version_check_up_to_date, current);
            } else {
                message = getString(R.string.version_check_ahead_of_release, current, latest);
            }
        }

        // Attribution for third-party artwork lives here rather than in the legal notice: that one
        // is shown once, on first launch, and never again, while a CC BY credit has to stay
        // reachable. This dialog is the app's de facto About screen - it sits behind the GitHub
        // icon and can be opened any time.
        message += "\n\n" + getString(R.string.credits_third_party);

        SpannableString s = new SpannableString(message);
        Linkify.addLinks(s, Linkify.WEB_URLS);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.version_check_title)
                .setMessage(s)
                .setPositiveButton(R.string.dialog_button_ok, null)
                .create();
        dialog.show();
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) {
            messageView.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }
}
