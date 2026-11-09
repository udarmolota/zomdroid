package com.zomdroid.fragments;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.zomdroid.R;
import com.zomdroid.steam.SteamDownloadState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Steam Workshop: the Project Zomboid Workshop itself in a WebView - Steam's own search, filters,
 * descriptions and screenshots - with a bar of ours at the bottom. On a mod (or collection) page
 * the bar offers "Add to queue"; "Download" hands the queue to the Steam download screen, which
 * fetches the items one after another with the anonymous mod downloader, as a list of ids typed
 * there would be. Nothing is scraped: the only thing read from the page is the item id in its URL.
 */
public class WorkshopFragment extends Fragment {
    private static final String START_URL = "https://steamcommunity.com/app/108600/workshop/";
    /** Item pages: .../sharedfiles/filedetails/?id=N and .../workshop/filedetails/?id=N. */
    private static final Pattern ITEM_URL = Pattern.compile("/filedetails/\\?(?:[^#]*&)?id=(\\d+)");

    /** id -> title, in the order added. Kept across visits to this screen in one app session. */
    private static final Map<Long, String> QUEUE = new LinkedHashMap<>();

    private WebView web;
    private ProgressBar progress;
    private TextView tvItem;
    private Button btnQueue;
    private Button btnDownload;
    private long currentId = -1;
    private String currentTitle;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_workshop, container, false);
    }

    @SuppressLint("SetJavaScriptEnabled") // Steam's Workshop is a script-driven page
    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        web = v.findViewById(R.id.wv_workshop);
        progress = v.findViewById(R.id.pb_workshop);
        tvItem = v.findViewById(R.id.tv_workshop_item);
        btnQueue = v.findViewById(R.id.btn_workshop_queue);
        btnDownload = v.findViewById(R.id.btn_workshop_download);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
            @Override public void onReceivedTitle(WebView view, String title) {
                if (currentId > 0) {
                    currentTitle = cleanTitle(title);
                    updateBar();
                }
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost() == null ? "" : uri.getHost();
                if (host.endsWith("steamcommunity.com") || host.endsWith("steampowered.com")
                        || host.endsWith("steamstatic.com")) {
                    return false;
                }
                // Links out of Steam (Discord, Ko-fi, GitHub in mod descriptions) go to the browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                    // no browser: stay put
                }
                return true;
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                onUrl(url, view.getTitle());
            }
        });

        btnQueue.setOnClickListener(x -> toggleCurrent());
        btnDownload.setOnClickListener(x -> confirmDownload());
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() {
                        if (web != null && web.canGoBack()) {
                            web.goBack();
                        } else {
                            setEnabled(false);
                            requireActivity().getOnBackPressedDispatcher().onBackPressed();
                        }
                    }
                });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
        updateBar();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    public void onDestroyView() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroyView();
    }

    private void onUrl(String url, String title) {
        Matcher m = url == null ? null : ITEM_URL.matcher(url);
        if (m != null && m.find()) {
            try {
                currentId = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                currentId = -1;
            }
            currentTitle = cleanTitle(title);
        } else {
            currentId = -1;
            currentTitle = null;
        }
        updateBar();
    }

    /** "Steam Workshop::Neat Crafting" -> "Neat Crafting". */
    @Nullable
    private static String cleanTitle(@Nullable String title) {
        if (title == null) return null;
        int sep = title.indexOf("::");
        String t = (sep >= 0 ? title.substring(sep + 2) : title).trim();
        return t.isEmpty() || t.startsWith("http") ? null : t;
    }

    private void toggleCurrent() {
        if (currentId <= 0) return;
        if (QUEUE.containsKey(currentId)) {
            QUEUE.remove(currentId);
        } else {
            QUEUE.put(currentId, currentTitle != null ? currentTitle : String.valueOf(currentId));
        }
        updateBar();
    }

    private void updateBar() {
        if (tvItem == null) return;
        boolean onItem = currentId > 0;
        if (onItem) {
            tvItem.setText(currentTitle != null ? currentTitle : getString(R.string.workshop_item_id, currentId));
            btnQueue.setVisibility(View.VISIBLE);
            btnQueue.setText(QUEUE.containsKey(currentId)
                    ? R.string.workshop_remove_from_queue : R.string.workshop_add_to_queue);
        } else {
            tvItem.setText(R.string.workshop_open_a_mod);
            btnQueue.setVisibility(View.GONE);
        }
        btnDownload.setEnabled(!QUEUE.isEmpty());
        btnDownload.setText(getString(R.string.workshop_download_n, QUEUE.size()));
    }

    private void confirmDownload() {
        if (QUEUE.isEmpty()) return;
        if (SteamDownloadState.get().isDownloading()) {
            Toast.makeText(requireContext(), R.string.workshop_download_busy, Toast.LENGTH_LONG).show();
            return;
        }
        List<String> titles = new ArrayList<>(QUEUE.values());
        StringBuilder list = new StringBuilder();
        for (String t : titles) list.append("• ").append(t).append('\n');
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.workshop_download_title, QUEUE.size()))
                .setMessage(getString(R.string.workshop_download_message, list.toString().trim()))
                .setPositiveButton(R.string.workshop_download_go, (d, w) -> startDownload())
                .setNeutralButton(R.string.workshop_clear_queue, (d, w) -> {
                    QUEUE.clear();
                    updateBar();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startDownload() {
        StringBuilder ids = new StringBuilder();
        for (Long id : QUEUE.keySet()) ids.append(id).append(' ');
        QUEUE.clear();
        Bundle args = new Bundle();
        args.putBoolean(SteamDownloadFragment.ARG_OPEN_MODS, true);
        args.putString(SteamDownloadFragment.ARG_MOD_IDS, ids.toString().trim());
        View root = getView();
        if (root != null) Navigation.findNavController(root).navigate(R.id.action_download_steam, args);
    }
}
