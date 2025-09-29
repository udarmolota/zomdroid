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
}
