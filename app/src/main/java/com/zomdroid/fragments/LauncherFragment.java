package com.zomdroid.fragments;

import static android.content.Context.MODE_PRIVATE;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zomdroid.C;
import com.zomdroid.GameActivity;
import com.zomdroid.InstallerService;
import com.zomdroid.R;

import com.zomdroid.databinding.FragmentLauncherBinding;
import com.zomdroid.databinding.TaskProgressDialogBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstancesManager;

public class LauncherFragment extends Fragment {
    private static final String LOG_TAG = LauncherFragment.class.getName();

    private FragmentLauncherBinding binding;
    private RecyclerView.Adapter<?> adapter;

    private TaskProgressDialogBinding taskProgressDialogBinding;
    private BroadcastReceiver taskProgressReceiver;
    private AlertDialog taskProgressDialog;

    private boolean isInstallerServiceBound;

    private final ServiceConnection installerServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            InstallerService.LocalBinder binder = (InstallerService.LocalBinder) service;
            InstallerService installerService = binder.getService();
            isInstallerServiceBound = true;

            handleTaskState(installerService.getTaskState().getValue());
            installerService.getTaskState().observe(LauncherFragment.this, this::handleTaskState);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Log.e(LOG_TAG, "Connection to installer service has been lost");
            isInstallerServiceBound = false;
            taskProgressDialog.dismiss();
        }

        private void handleTaskState(InstallerService.TaskState state) {
            if (state == null)
                return;
            if (state.isFinished) {
                adapter.notifyDataSetChanged();
                taskProgressDialog.dismiss();
                unbindInstallerService();
                requireContext().stopService(new Intent(requireContext(), InstallerService.class));
            } else if (state.isFinishedWithError) {
                adapter.notifyDataSetChanged();
                showTaskFinishedDialog(state.title, state.message);
                unbindInstallerService();
                requireContext().stopService(new Intent(requireContext(), InstallerService.class));
            } else {
                showTaskProgressDialog(state.title, state.message, state.progress, state.progressMax);
            }
        }
    };

    private ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    // Explain to user why we need the permission?
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentLauncherBinding.inflate(inflater, container, false);
        return binding.getRoot();

    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new RecyclerView.Adapter<>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.game_instance_item, parent, false);
                return new RecyclerView.ViewHolder(view) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                GameInstance gameInstance = GameInstancesManager.requireSingleton().getInstances().get(position);
                View itemView = holder.itemView;

                TextView nameTv = itemView.findViewById(R.id.game_instance_item_name_tv);
                ImageButton launchIb = itemView.findViewById(R.id.game_instance_item_launch_ib);
                ImageButton settingsIb = itemView.findViewById(R.id.game_instance_item_settings_ib);

                nameTv.setText(gameInstance.getName());

                launchIb.setOnClickListener(v -> {
                    if (!gameInstance.isInstalled()) {
                        Toast.makeText(getContext(), R.string.game_instance_not_installed,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean areDependenciesInstalled = requireContext().getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE)
                            .getBoolean(C.shprefs.keys.ARE_DEPENDENCIES_INSTALLED, false);
                    if (!areDependenciesInstalled) {
                        Toast.makeText(getContext(), R.string.dependencies_not_installed,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent intent = new Intent(requireContext(), GameActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    intent.putExtra(GameActivity.EXTRA_GAME_INSTANCE_NAME, gameInstance.getName());
                    startActivity(intent);
                    requireActivity().finish();
                });

                settingsIb.setOnClickListener(v -> {
                    PopupMenu popupMenu = new PopupMenu(requireContext(), v);
                    popupMenu.getMenuInflater().inflate(R.menu.menu_game_instance, popupMenu.getMenu());

                    popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                            int itemId = item.getItemId();
                            if (itemId == R.id.action_game_instance_manage_storage) {
                                Uri folderUri = DocumentsContract.buildDocumentUri(C.STORAGE_PROVIDER_AUTHORITY, gameInstance.getHomePath());
                                Intent intent = new Intent();
                                intent.setAction(Intent.ACTION_VIEW);
                                intent.setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR);
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                Intent chooserIntent = Intent.createChooser(intent, null);
                                startActivity(chooserIntent);
                            } else if (itemId == R.id.action_game_instance_delete) {
                                Intent gameInstallerIntent = new Intent(requireContext(), InstallerService.class);
                                gameInstallerIntent.putExtra(InstallerService.EXTRA_COMMAND, InstallerService.Task.DELETE_GAME_INSTANCE.ordinal());
                                gameInstallerIntent.putExtra(InstallerService.EXTRA_GAME_INSTANCE_NAME, gameInstance.getName());
                                requireContext().startForegroundService(gameInstallerIntent);
                            }
                            return false;
                        }
                    });

                    popupMenu.show();
                });
            }

            @Override
            public int getItemCount() {
                return GameInstancesManager.requireSingleton().getInstances().size();
            }
        };
        binding.gameInstancesRv.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.gameInstancesRv.setAdapter(adapter);

        binding.gameInstancesRv.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @NonNull
            @Override
            public WindowInsets onApplyWindowInsets(@NonNull View v, @NonNull WindowInsets windowInsets) {
                Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(
                        v.getPaddingLeft(),
                        v.getPaddingTop(),
                        v.getPaddingRight(),
                        insets.bottom
                );

                return windowInsets;
            }
        });

        MenuHost menuHost = requireActivity();
        menuHost.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.menu_launcher, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.action_new_game_instance) {
                    Navigation.findNavController(view).navigate(R.id.new_game_instance_fragment);
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        taskProgressDialogBinding = TaskProgressDialogBinding.inflate(getLayoutInflater());

        taskProgressDialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(taskProgressDialogBinding.getRoot())
                .setCancelable(false)
                .create();

        taskProgressDialogBinding.progressDialogOkMb.setOnClickListener(v -> {
            taskProgressDialog.dismiss();
            adapter.notifyDataSetChanged();
        });

        taskProgressReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                if (action.equals(InstallerService.ACTION_STARTED)) {
                    bindInstallerService();
                }
            }
        };

        SharedPreferences prefs = requireContext().getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(C.shprefs.keys.IS_LEGAL_NOTICE_ACCEPTED, false)) {
            showLegalNoticeDialog();
            requireActivity().findViewById(android.R.id.content).setVisibility(View.GONE);
        } else {
            updateDependencies();
        }

    }

    private void showLegalNoticeDialog() {
        AlertDialog legalNoticeDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.legal_notice_title)
                .setMessage(R.string.legal_notice_message)
                .setCancelable(false)
                .setPositiveButton(R.string.dialog_button_accept, (dialog, which) -> {
                    dialog.dismiss();
                    requireContext().getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE)
                            .edit().putBoolean(C.shprefs.keys.IS_LEGAL_NOTICE_ACCEPTED, true).apply();
                    requireActivity().findViewById(android.R.id.content).setVisibility(View.VISIBLE);
                    updateDependencies();
                    requestNotificationPermission();
                })
                .create();
        legalNoticeDialog.show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void showTaskProgressDialog(String title, String message, int progress, int progressMax) {
        if (title != null) {
            taskProgressDialogBinding.progressDialogTitleTv.setText(title);
            taskProgressDialogBinding.progressDialogTitleTv.setVisibility(View.VISIBLE);
        } else {
            taskProgressDialogBinding.progressDialogTitleTv.setVisibility(View.GONE);
        }

        if (message != null) {
            taskProgressDialogBinding.progressDialogMessageTv.setText(message);
            taskProgressDialogBinding.progressDialogMessageTv.setVisibility(View.VISIBLE);
        } else {
            taskProgressDialogBinding.progressDialogMessageTv.setVisibility(View.GONE);
        }

        taskProgressDialogBinding.progressDialogProgressLpi.setVisibility(View.VISIBLE);
        if (progress < 0)
            taskProgressDialogBinding.progressDialogProgressLpi.setIndeterminate(true);
        else {
            taskProgressDialogBinding.progressDialogProgressLpi.setIndeterminate(false);
            taskProgressDialogBinding.progressDialogProgressLpi.setMax(progressMax);
            taskProgressDialogBinding.progressDialogProgressLpi.setProgress(progress);
        }

        taskProgressDialogBinding.progressDialogOkMb.setVisibility(View.GONE);

        taskProgressDialog.show();
    }

    private void showTaskFinishedDialog(String title, String message) {
        if (title != null) {
            taskProgressDialogBinding.progressDialogTitleTv.setText(title);
            taskProgressDialogBinding.progressDialogTitleTv.setVisibility(View.VISIBLE);
        } else {
            taskProgressDialogBinding.progressDialogTitleTv.setVisibility(View.GONE);
        }

        if (message != null) {
            taskProgressDialogBinding.progressDialogMessageTv.setText(message);
            taskProgressDialogBinding.progressDialogMessageTv.setVisibility(View.VISIBLE);
        } else {
            taskProgressDialogBinding.progressDialogMessageTv.setVisibility(View.GONE);
        }

        taskProgressDialogBinding.progressDialogProgressLpi.setVisibility(View.GONE);

        taskProgressDialogBinding.progressDialogOkMb.setVisibility(View.VISIBLE);

        taskProgressDialog.show();
    }

    private void updateDependencies() {
        boolean areDependenciesInstalled = requireContext().getSharedPreferences(C.shprefs.NAME, MODE_PRIVATE)
                .getBoolean(C.shprefs.keys.ARE_DEPENDENCIES_INSTALLED, false);
        if (!areDependenciesInstalled) {
            Intent installerIntent = new Intent(requireContext(), InstallerService.class);
            installerIntent.putExtra(InstallerService.EXTRA_COMMAND, InstallerService.Task.INSTALL_DEPENDENCIES.ordinal());
            requireContext().startForegroundService(installerIntent);
        }
    }

    private void bindInstallerService() {
        Intent intent = new Intent(requireContext(), InstallerService.class);
        requireContext().bindService(intent, this.installerServiceConnection, 0);
    }

    private void unbindInstallerService() {
        if (this.isInstallerServiceBound) {
            requireContext().unbindService(this.installerServiceConnection);
            isInstallerServiceBound = false;
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        bindInstallerService();

        IntentFilter filter = new IntentFilter();
        filter.addAction(InstallerService.ACTION_STARTED);
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(taskProgressReceiver, filter);
    }

    @Override
    public void onPause() {
        super.onPause();

        unbindInstallerService();

        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(taskProgressReceiver);
    }
}
