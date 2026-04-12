package com.zomdroid;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
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
import com.zomdroid.game.GameInstanceManager;
import com.zomdroid.input.AbstractControlElement;
import com.zomdroid.input.ControlElementDescription;
import com.zomdroid.input.GamepadManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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

        setSupportActionBar(binding.appbar);

        appBarConfiguration = new AppBarConfiguration.Builder(
                R.id.launcher_fragment)
                .setOpenableLayout(binding.drawerLayout)
                .build();

        navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
        NavigationUI.setupWithNavController(binding.launcherNv, navController);


        binding.launcherNv.setNavigationItemSelectedListener(item -> {
            if (item.getItemId() == R.id.action_manage_storage) {
                Uri folderUri = DocumentsContract.buildDocumentUri(C.STORAGE_PROVIDER_AUTHORITY, AppStorage.requireSingleton().getHomePath());
                Intent intent = new Intent();
                intent.setAction(Intent.ACTION_VIEW);
                intent.setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                Intent chooserIntent = Intent.createChooser(intent, null);
                startActivity(chooserIntent);
                return true;
            } else if (item.getItemId() == R.id.action_open_controls_editor) {
                Intent intent = new Intent(this, ControlsEditorActivity.class);
                startActivity(intent);
                return true;
            } else if (item.getItemId() == R.id.action_open_gamepad_mapper) {
                // Navigate to gamepad mapper using NavController
                binding.drawerLayout.close();
                navController.navigate(R.id.action_open_gamepad_mapper);
                return true;
            } else if (item.getItemId() == R.id.action_donate) {
                final SpannableString s = new SpannableString(getString(R.string.donate_message));
                Linkify.addLinks(s, Linkify.WEB_URLS);
                AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.dialog_title_donate)
                        .setMessage(s)
                        .setPositiveButton(getString(R.string.dialog_button_ok), null)
                        .create();
                dialog.show();
                TextView messageView = dialog.findViewById(android.R.id.message);
                if (messageView != null) {
                    messageView.setMovementMethod(LinkMovementMethod.getInstance());
                }
                return true;
            } else if (item.getItemId() == R.id.action_reddit) {
                final SpannableString s = new SpannableString(getString(R.string.reddit_message));
                Linkify.addLinks(s, Linkify.WEB_URLS);
                AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.reddit_dialog_title)
                        .setMessage(s)
                        .setPositiveButton(getString(R.string.dialog_button_ok), null)
                        .create();
                dialog.show();
                TextView messageView = dialog.findViewById(android.R.id.message);
                if (messageView != null) {
                    messageView.setMovementMethod(LinkMovementMethod.getInstance());
                }
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
            } else if (item.getItemId() == R.id.action_version) {
                checkForUpdate();
                return true;
        }

        binding.drawerLayout.close();

            return NavigationUI.onNavDestinationSelected(item, navController)
                    || super.onOptionsItemSelected(item);
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    private void checkForUpdate() {
        new Thread(() -> {
            try {
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

                JSONObject json = new JSONObject(sb.toString());
                String latestTag = json.getString("tag_name"); // "v1.4.1"
                String releaseUrl = json.getString("html_url");
                String latest = latestTag.startsWith("v") ? latestTag.substring(1) : latestTag;
                String current = BuildConfig.VERSION_NAME;

                runOnUiThread(() -> showVersionDialog(current, latest, releaseUrl));

            } catch (Exception e) {
                runOnUiThread(() -> showVersionDialog(BuildConfig.VERSION_NAME, null, null));
            }
        }).start();
    }

    private void showVersionDialog(String current, String latest, String releaseUrl) {
        String message;
        if (latest == null) {
            message = getString(R.string.version_check_error, current);
        } else if (current.equals(latest)) {
            message = getString(R.string.version_check_up_to_date, current);
        } else {
            message = getString(R.string.version_check_update_available, current, latest, releaseUrl);
        }

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
