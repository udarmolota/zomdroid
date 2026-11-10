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
    /** Above this many mods the download confirmation warns about time and storage. */
    private static final int MANY_MODS = 30;

    private static final String PREFS = "workshop";
    private static final String KEY_TEXT_ZOOM = "text_zoom";
    private static final int DEFAULT_TEXT_ZOOM = 80;

    private String buttonsScript;
    private WebView web;
    private ProgressBar progress;
    /** The "no connection" panel over the page, and whether the page now loading has failed. */
    private View offline;
    private boolean pageFailed;
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
                injectButtons();   // Steam changes pages without reloading them
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageFailed = false;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                  android.webkit.WebResourceError error) {
                // Only the page itself: a failed picture or script inside a page is not "offline".
                if (request.isForMainFrame()) {
                    pageFailed = true;
                    offline.setVisibility(View.VISIBLE);
                }
            }
            @Override public void onPageFinished(WebView view, String url) {
                offline.setVisibility(pageFailed ? View.VISIBLE : View.GONE);
                injectButtons();
            }
        });
        offline = v.findViewById(R.id.ll_workshop_offline);
        v.findViewById(R.id.btn_workshop_retry).setOnClickListener(x -> {
            offline.setVisibility(View.GONE);
            String url = web.getUrl();
            if (url == null || url.startsWith("data:") || url.startsWith("about:")) web.loadUrl(START_URL);
            else web.reload();
        });

        // Text size: Steam's layout is made for a desktop, so it starts smaller and is remembered.
        android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        ws.setTextZoom(prefs.getInt(KEY_TEXT_ZOOM, DEFAULT_TEXT_ZOOM));
        v.findViewById(R.id.btn_workshop_text_smaller).setOnClickListener(x -> zoomText(prefs, -10));
        v.findViewById(R.id.btn_workshop_text_larger).setOnClickListener(x -> zoomText(prefs, +10));

        // The ↓ buttons on every card call back into this bridge (see assets/workshop/queue-buttons.js).
        web.addJavascriptInterface(new Bridge(), "ZomdroidWorkshop");

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

    @Override
    public void onResume() {
        super.onResume();
        injectButtons();   // the queue may have been emptied by a download meanwhile
    }

    private void zoomText(android.content.SharedPreferences prefs, int delta) {
        if (web == null) return;
        int zoom = Math.max(50, Math.min(150, web.getSettings().getTextZoom() + delta));
        web.getSettings().setTextZoom(zoom);
        prefs.edit().putInt(KEY_TEXT_ZOOM, zoom).apply();
    }

    /** Adds (or refreshes) the ↓ buttons on the cards of the page being shown. */
    private void injectButtons() {
        if (web == null) return;
        String url = web.getUrl();
        String host = url == null ? null : Uri.parse(url).getHost();
        if (host == null || !host.endsWith("steamcommunity.com")) return;
        if (buttonsScript == null) {
            try (java.io.InputStream in = requireContext().getAssets().open("workshop/queue-buttons.js")) {
                buttonsScript = new String(readAll(in), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                android.util.Log.w("WorkshopFragment", "No queue-buttons.js", e);
                return;
            }
        }
        web.evaluateJavascript(buttonsScript, null);
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /**
     * What the page script may call. Runs on a WebView background thread; the queue is touched
     * only under its own lock and the bar is updated on the main thread. The WebView only ever
     * shows Steam's own sites (everything else opens in the browser), and all the page can do
     * through this is add or remove a Workshop id in our queue.
     */
    private final class Bridge {
        @android.webkit.JavascriptInterface
        public boolean isQueued(String id) {
            long n = parseId(id);
            synchronized (QUEUE) { return n > 0 && QUEUE.containsKey(n); }
        }

        @android.webkit.JavascriptInterface
        public boolean toggle(String id, String title) {
            long n = parseId(id);
            if (n <= 0) return false;
            boolean queued;
            synchronized (QUEUE) {
                if (QUEUE.containsKey(n)) {
                    QUEUE.remove(n);
                    queued = false;
                } else {
                    String t = title == null ? "" : title.trim();
                    if (t.length() > 120) t = t.substring(0, 120);
                    QUEUE.put(n, t.isEmpty() ? String.valueOf(n) : t);
                    queued = true;
                }
            }
            View root = getView();
            if (root != null) root.post(WorkshopFragment.this::updateBar);
            return queued;
        }

        private long parseId(String id) {
            try {
                return id == null ? -1 : Long.parseLong(id.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
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
        synchronized (QUEUE) {
            if (QUEUE.containsKey(currentId)) {
                QUEUE.remove(currentId);
            } else {
                QUEUE.put(currentId, currentTitle != null ? currentTitle : String.valueOf(currentId));
            }
        }
        updateBar();
        injectButtons();   // repaint the same item's button in lists further back
    }

    private void updateBar() {
        if (tvItem == null) return;
        boolean onItem = currentId > 0;
        boolean queued;
        int size;
        synchronized (QUEUE) {
            queued = QUEUE.containsKey(currentId);
            size = QUEUE.size();
        }
        if (onItem) {
            tvItem.setText(currentTitle != null ? currentTitle : getString(R.string.workshop_item_id, currentId));
            btnQueue.setVisibility(View.VISIBLE);
            btnQueue.setText(queued ? R.string.workshop_remove_from_queue : R.string.workshop_add_to_queue);
        } else {
            tvItem.setText(R.string.workshop_open_a_mod);
            btnQueue.setVisibility(View.GONE);
        }
        btnDownload.setEnabled(size > 0);
        btnDownload.setText(getString(R.string.workshop_download_n, size));
    }

    private void confirmDownload() {
        List<String> titles;
        synchronized (QUEUE) { titles = new ArrayList<>(QUEUE.values()); }
        if (titles.isEmpty()) return;
        if (SteamDownloadState.get().isDownloading()) {
            Toast.makeText(requireContext(), R.string.workshop_download_busy, Toast.LENGTH_LONG).show();
            return;
        }
        StringBuilder list = new StringBuilder();
        for (String t : titles) list.append("• ").append(t).append('\n');
        String message = getString(R.string.workshop_download_message, list.toString().trim());
        // No hard cap (collections bypass it anyway): a big queue only gets a warning about time and space.
        if (titles.size() > MANY_MODS) {
            message = getString(R.string.workshop_download_many, titles.size()) + "\n\n" + message;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.workshop_download_title, titles.size()))
                .setMessage(message)
                .setPositiveButton(R.string.workshop_download_go, (d, w) -> startDownload())
                .setNeutralButton(R.string.workshop_clear_queue, (d, w) -> {
                    synchronized (QUEUE) { QUEUE.clear(); }
                    updateBar();
                    injectButtons();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startDownload() {
        StringBuilder ids = new StringBuilder();
        synchronized (QUEUE) {
            for (Long id : QUEUE.keySet()) ids.append(id).append(' ');
            QUEUE.clear();
        }
        Bundle args = new Bundle();
        args.putBoolean(SteamDownloadFragment.ARG_OPEN_MODS, true);
        args.putString(SteamDownloadFragment.ARG_MOD_IDS, ids.toString().trim());
        View root = getView();
        if (root != null) Navigation.findNavController(root).navigate(R.id.action_download_steam, args);
    }
}
