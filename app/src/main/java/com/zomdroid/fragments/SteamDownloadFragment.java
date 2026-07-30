package com.zomdroid.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.zomdroid.R;
import com.zomdroid.steam.SteamDownloadState;
import com.zomdroid.steam.SteamGameDownloader;
import com.zomdroid.steam.SteamModDownloader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Download from Steam" screen. The actual download runs on a background thread tracked by
 * {@link SteamDownloadState}, so leaving and returning keeps the log and "still downloading" state.
 */
public class SteamDownloadFragment extends Fragment implements SteamDownloadState.View {

    private EditText etUser, etPass, etManifest, etModsIds;
    private Button btnStart, btnModsStart, btnCancel;
    private ProgressBar progress;
    private TextView tvStatus;
    private android.view.View blockLogin, sectionGame, sectionMods;
    private MaterialButtonToggleGroup buildToggle;
    private Context appCtx;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_steam_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        appCtx = requireContext().getApplicationContext();
        etUser = v.findViewById(R.id.et_dl_user);
        etPass = v.findViewById(R.id.et_dl_pass);
        etManifest = v.findViewById(R.id.et_dl_manifest);
        etModsIds = v.findViewById(R.id.et_mods_ids);
        btnStart = v.findViewById(R.id.btn_dl_start);
        btnModsStart = v.findViewById(R.id.btn_mods_start);
        btnCancel = v.findViewById(R.id.btn_dl_cancel);
        progress = v.findViewById(R.id.progress_dl);
        tvStatus = v.findViewById(R.id.tv_dl_status);
        blockLogin = v.findViewById(R.id.block_login);
        sectionGame = v.findViewById(R.id.section_game);
        sectionMods = v.findViewById(R.id.section_mods);

        tvStatus.setMovementMethod(new ScrollingMovementMethod());
        btnStart.setOnClickListener(view -> startGame());
        btnModsStart.setOnClickListener(view -> startMods());
        btnCancel.setOnClickListener(view -> confirmCancel());

        MaterialButtonToggleGroup toggle = v.findViewById(R.id.toggle_dl_type);
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean game = checkedId == R.id.btn_type_game;
            sectionGame.setVisibility(game ? View.VISIBLE : View.GONE);
            sectionMods.setVisibility(game ? View.GONE : View.VISIBLE);
            blockLogin.setVisibility(game ? View.VISIBLE : View.GONE);
        });
        toggle.check(R.id.btn_type_game);

        buildToggle = v.findViewById(R.id.toggle_dl_build);
        buildToggle.check(R.id.btn_build_41);   // legacy41 is the default selection

        // A pinned manifest fully determines what gets downloaded (see parseManifestId /
        // parseBranchOverride below), so the toggle is redundant — and potentially misleading,
        // since toggling it while a manifest is entered would not actually change anything.
        etManifest.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                boolean pinned = !s.toString().trim().isEmpty();
                buildToggle.setEnabled(!pinned);
                for (int i = 0; i < buildToggle.getChildCount(); i++) {
                    buildToggle.getChildAt(i).setEnabled(!pinned);
                }
            }
        });

        // Re-attach to the running download (if any) and restore its log + progress + state.
        SteamDownloadState st = SteamDownloadState.get();
        st.setView(this);
        tvStatus.setText(st.getLog());
        scrollLogToBottom();
        boolean busy = st.isDownloading();
        setControlsEnabled(!busy);
        btnCancel.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            progress.setVisibility(View.VISIBLE);
            if (st.isIndeterminate() || st.getPercent() < 0) {
                progress.setIndeterminate(true);
            } else {
                progress.setIndeterminate(false);
                progress.setProgress(st.getPercent());
            }
        } else {
            progress.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        SteamDownloadState.get().clearView(this);
        super.onDestroyView();
    }

    // SteamDB's depot "Manifests" tab offers two copy formats per row; only "DepotDownloader"
    // carries the branch, e.g.:
    //   -app 108600 -depot 108603 -manifest 769556489232787342 -beta legacy41
    // ("Steam Console" format has no branch and is not usable here.) A bare number is also
    // accepted, unchanged from before. -beta is absent when the manifest was on "public" at
    // capture time — SteamDB omits it there since that is DepotDownloader's own default branch.
    private static final Pattern MANIFEST_ARG = Pattern.compile("-manifest\\s+(\\d+)");
    private static final Pattern BETA_ARG = Pattern.compile("-beta\\s+(\\S+)");

    private static long parseManifestId(String raw) {
        if (raw.matches("\\d+")) {
            try { return Long.parseLong(raw); } catch (NumberFormatException ignored) { return 0L; }
        }
        Matcher m = MANIFEST_ARG.matcher(raw);
        if (m.find()) {
            try { return Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    private static String parseBranchOverride(String raw) {
        Matcher m = BETA_ARG.matcher(raw);
        return m.find() ? m.group(1) : null;
    }

    // ---- start ----
    private void startGame() {
        if (SteamDownloadState.get().isDownloading()) return;
        if (text(etUser).isEmpty()) { etUser.setError(getString(R.string.steam_dl_required)); return; }
        if (etPass.getText().toString().isEmpty()) { etPass.setError(getString(R.string.steam_dl_required)); return; }
        if (!ensureAllFilesAccess()) return;

        String manifestText = text(etManifest);
        long manifestId = parseManifestId(manifestText);
        String branchOverride = manifestId > 0 ? parseBranchOverride(manifestText) : null;

        String branch;
        String buildLabel;
        if (manifestId > 0) {
            // A pinned manifest fully determines what gets downloaded — the Build 41/42 toggle is
            // not consulted (and is disabled while this field is non-empty, see the TextWatcher in
            // onViewCreated). "public" is a safe default authorization branch when the pasted line
            // has no -beta: that happens for manifests that were on public at the time they were
            // captured on SteamDB, since public is DepotDownloader's own default branch.
            branch = branchOverride != null ? branchOverride : "public";
            buildLabel = branchOverride != null ? branchOverride : "manifest";
        } else {
            boolean is42 = buildToggle.getCheckedButtonId() == R.id.btn_build_42;
            // As of the 2026-07-29 42.20 release: TIS renamed the old rolling beta ("unstable") to
            // "outdatedunstable" and stopped updating it, moved Build 42 onto public (so public now
            // *is* whatever the newest 42.x is, tracking it automatically as TIS ships more), and
            // designated "legacy41" as the permanent, officially-announced home for Build 41 going
            // forward. Confirmed live via the branch listing SteamGameDownloader logs at download
            // time — don't hardcode from a screenshot again, branches get reshuffled without notice.
            branch = is42 ? "public" : "legacy41";
            buildLabel = is42 ? "42" : "41";
        }

        SteamDownloadState st = SteamDownloadState.get();
        SteamGameDownloader dl = new SteamGameDownloader(text(etUser), etPass.getText().toString(),
                manifestId, branch, buildLabel, st);
        Thread th = new Thread(dl, "zd-download");
        st.begin(appCtx);
        st.setActive(dl, th);
        beginUi();
        th.start();
    }

    private void startMods() {
        if (SteamDownloadState.get().isDownloading()) return;
        List<Long> ids = new ArrayList<>();
        for (String tok : text(etModsIds).split("[\\s,]+")) {
            if (tok.isEmpty()) continue;
            try { ids.add(Long.parseLong(tok)); } catch (NumberFormatException ignored) {}
        }
        if (ids.isEmpty()) {
            Toast.makeText(requireContext(), R.string.steam_dl_mods_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureAllFilesAccess()) return;

        SteamDownloadState st = SteamDownloadState.get();
        SteamModDownloader dl = new SteamModDownloader(ids, st);
        Thread th = new Thread(dl, "zd-anon-mod");
        st.begin(appCtx);
        st.setActive(dl, th);
        beginUi();
        th.start();
    }

    private void beginUi() {
        setControlsEnabled(false);
        btnCancel.setVisibility(View.VISIBLE);
        tvStatus.setText("");
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        appendLog("Connecting to Steam…");
    }

    private void confirmCancel() {
        new AlertDialog.Builder(requireActivity())
                .setMessage(R.string.steam_dl_cancel_confirm)
                .setPositiveButton(R.string.steam_dl_cancel, (d, w) -> SteamDownloadState.get().cancel())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setControlsEnabled(boolean enabled) {
        if (etUser != null) etUser.setEnabled(enabled);
        if (etPass != null) etPass.setEnabled(enabled);
        if (etManifest != null) etManifest.setEnabled(enabled);
        if (btnStart != null) btnStart.setEnabled(enabled);
        if (btnModsStart != null) btnModsStart.setEnabled(enabled);
    }

    // ---- All-files access (writes into the public Downloads/zomdroid folder) ----
    private boolean ensureAllFilesAccess() {
        if (Environment.isExternalStorageManager()) return true;
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.steam_dl_storage_title)
                .setMessage(R.string.steam_dl_storage_message)
                .setPositiveButton(R.string.steam_dl_grant, (d, w) -> requestAllFilesAccess())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        return false;
    }

    private void requestAllFilesAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + requireContext().getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // ---- SteamDownloadState.View (always on main thread) ----
    @Override
    public void onLog(CharSequence fullLog) {
        if (tvStatus == null) return;
        tvStatus.setText(fullLog);
        scrollLogToBottom();
    }

    @Override
    public void onPercent(int percent, boolean indeterminate) {
        if (progress == null) return;
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(indeterminate);
        if (!indeterminate) progress.setProgress(Math.max(0, Math.min(100, percent)));
    }

    @Override
    public void onFinished(String message) {
        setControlsEnabled(true);
        if (progress != null) progress.setVisibility(View.GONE);
        if (btnCancel != null) btnCancel.setVisibility(View.GONE);
        Toast.makeText(appCtx, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public CompletableFuture<String> requestSteamGuardCode(boolean prevWrong, String email) {
        final CompletableFuture<String> fut = new CompletableFuture<>();
        if (!isAdded()) { fut.complete(""); return fut; }
        final EditText etCode = new EditText(requireActivity());
        etCode.setHint(email != null
                ? getString(R.string.steam_dl_guard_email, email)
                : getString(R.string.steam_dl_guard_hint));
        etCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(requireActivity())
                .setTitle(prevWrong ? R.string.steam_dl_guard_retry : R.string.steam_dl_guard_title)
                .setView(etCode)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> fut.complete(etCode.getText().toString().trim()))
                .show();
        return fut;
    }

    private void appendLog(String line) {
        if (tvStatus == null) return;
        tvStatus.append(line + "\n");
        scrollLogToBottom();
    }

    private void scrollLogToBottom() {
        if (tvStatus == null) return;
        android.text.Layout layout = tvStatus.getLayout();
        if (layout != null) {
            int y = layout.getLineTop(tvStatus.getLineCount()) - tvStatus.getHeight();
            tvStatus.scrollTo(0, Math.max(0, y));
        }
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }
}
