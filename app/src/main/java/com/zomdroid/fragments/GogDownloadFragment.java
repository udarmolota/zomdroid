package com.zomdroid.fragments;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.zomdroid.R;
import com.zomdroid.gog.GogAuth;
import com.zomdroid.gog.GogDownloadQueue;
import com.zomdroid.gog.GogDownloader;
import com.zomdroid.gog.GogLibrary;
import com.zomdroid.steam.SteamGameDownloader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * "Download from GOG": a WebView on GOG's own login page, then the account's library as cards.
 * Ported from RimDroid (MIT).
 *
 * <p>The password is never seen here: GOG's page handles it, along with the CAPTCHA, e-mail
 * two-step and TOTP prompts a headless login could not clear. The screen only watches for the
 * navigation to Galaxy's redirect target and takes the authorisation code out of it; the cookies
 * the page leaves behind in Android's store are the other half of the credentials (see
 * {@link GogAuth}).
 *
 * <p>When a session already exists the WebView is skipped and the screen lists what the account
 * owns that Zomdroid can install: one card per Linux installer. A card's button follows its file:
 * download, queued, percentage, then "Create an instance", which opens the new-instance screen with
 * the installer already chosen. The transfers belong to {@link GogDownloadQueue}, not to this
 * screen, so leaving it and coming back loses nothing.
 */
public class GogDownloadFragment extends Fragment implements GogDownloadQueue.Listener {
    private static final String TAG = "Zomdroid/GOG";

    /**
     * How many products one scan will open. Titles are only knowable by opening each product (see
     * {@link #probeLibrary}), so this is a ceiling on requests, not on what the user owns.
     */
    private static final int SCAN_LIMIT = 100;

    /**
     * The last scan's result, kept for the life of the process: coming back to this screen then
     * shows the cards, and any download still running, straight away instead of an empty list and
     * another round of requests.
     */
    private static volatile List<GogLibrary.Product> lastScan;
    private static volatile boolean lastScanFellBack;

    private WebView web;
    private ProgressBar progress;
    private TextView status;
    private View signedInBlock;
    /** "User ID: …" under "Signed in to GOG" in the signed-in block. */
    private TextView userLine;
    /** The redirect fires for the page load AND its sub-resources; only act on the first one. */
    private boolean codeTaken = false;
    /** True while www.gog.com is being loaded purely to make GOG hand over a www session cookie. */
    private boolean establishingSession = false;
    /** Holds one item_gog_game card per installer. */
    private ViewGroup gameList;
    /** The scroll around gameList; visible only while signed in (see showSignedIn). */
    private View gameScroll;
    /** The cards on screen, so that a queue update can restyle them in place. */
    private final List<Card> cards = new ArrayList<>();
    /**
     * Worker threads reach the UI only through this (see {@link #onUi}). requireActivity() and
     * getString() both throw once the user has left the screen, and a scan can easily outlive it.
     */
    private final Handler main = new Handler(Looper.getMainLooper());

    /** One card on screen and the installer behind it. */
    private static final class Card {
        final GogLibrary.Product game;
        final GogLibrary.File file;
        final TextView subtitle;
        final Button button;
        /** Size and version: what the subtitle says whenever there is no error to report. */
        final String details;

        Card(GogLibrary.Product game, GogLibrary.File file, TextView subtitle, Button button, String details) {
            this.game = game;
            this.file = file;
            this.subtitle = subtitle;
            this.button = button;
            this.details = details;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_gog_download, container, false);
    }

    @Override
    @SuppressLint("SetJavaScriptEnabled")   // GOG's login page does not work without it
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        GogLibrary.init(requireContext());
        web           = v.findViewById(R.id.web_gog);
        progress      = v.findViewById(R.id.progress_gog);
        status        = v.findViewById(R.id.tv_gog_status);
        signedInBlock = v.findViewById(R.id.block_gog_signed_in);
        userLine      = v.findViewById(R.id.tv_gog_user);
        gameList      = v.findViewById(R.id.gog_game_list);
        gameScroll    = v.findViewById(R.id.scroll_gog_games);

        // Disclaimer card: collapsed by default; tapping the header expands or collapses it and the
        // arrow turns to match. Wired before the signed-in early return below, because the card is
        // on screen in both states.
        final View discText = v.findViewById(R.id.tv_gog_disclaimer_text);
        final ImageView discArrow = v.findViewById(R.id.gog_disclaimer_expand_iv);
        v.findViewById(R.id.gog_disclaimer_header).setOnClickListener(x -> {
            boolean show = discText.getVisibility() != View.VISIBLE;
            discText.setVisibility(show ? View.VISIBLE : View.GONE);
            discArrow.setRotation(show ? 180 : 0);
        });

        v.findViewById(R.id.btn_gog_check).setOnClickListener(x -> probeLibrary());
        v.findViewById(R.id.btn_gog_sign_out).setOnClickListener(x -> {
            GogAuth.signOut();
            lastScan = null;
            status.setText("");
            clearCards();
            showSignedIn(false);
            codeTaken = false;
            web.loadUrl(GogAuth.authUrl());
        });

        GogDownloadQueue.get().setListener(this);

        if (GogAuth.isSignedIn()) {
            showSignedIn(true);
            List<GogLibrary.Product> shown = lastScan;
            // Who is signed in is shown in the signed-in block; the status line is for notes only.
            status.setText(shown != null && lastScanFellBack ? getString(R.string.gog_no_game) : "");
            if (shown != null) render(shown);
            return;
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handle(request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // The redirect can arrive as a plain navigation rather than one to override, so
                // check here too. handle() is idempotent.
                handle(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (establishingSession && url != null && url.contains("gog.com")) finishSignIn();
            }
        });

        web.loadUrl(GogAuth.authUrl());
    }

    /**
     * Swap between the login WebView and the signed-in controls. The game list and the WebView share
     * the leftover height, so exactly one of them may be visible.
     */
    private void showSignedIn(boolean signedIn) {
        signedInBlock.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        gameScroll.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        web.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        if (signedIn) userLine.setText(getString(R.string.gog_user_id, String.valueOf(GogAuth.userId())));
    }

    /** @return true when the URL was the login callback and it was consumed. */
    private boolean handle(String url) {
        String code = GogAuth.codeFrom(url);
        if (code == null || codeTaken) return false;
        codeTaken = true;

        web.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.gog_login_finishing);

        new Thread(() -> {
            String error = null;
            try {
                // Flush now: the tokens are useless without the cookies the page just set, and
                // Android writes them out lazily.
                CookieManager.getInstance().flush();
                GogAuth.signInWithCode(code);
            } catch (Exception e) {
                Log.e(TAG, "sign-in failed", e);
                error = e.getMessage();
            }
            final String err = error;
            onUi(() -> {
                if (err == null) {
                    establishSession();
                } else {
                    progress.setVisibility(View.GONE);
                    status.setText(getString(R.string.gog_login_failed, err));
                }
            });
        }, "zd-gog-token").start();
        return true;
    }

    /**
     * Having the tokens is not enough to read the library: those endpoints live on www.gog.com and
     * authorise by cookie, but the OAuth flow only ever touched auth/login/embed, so no www cookie
     * exists yet. Load one www page so GOG turns that login into a www session. If it never
     * finishes, carry on anyway: GogAuth.cookieHeader() falls back to sending the login-host
     * cookies, which may well be accepted.
     */
    private void establishSession() {
        establishingSession = true;
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.gog_login_finishing);
        web.loadUrl("https://www.gog.com/account");
        status.postDelayed(() -> { if (establishingSession) finishSignIn(); }, 15000);
    }

    /** Settle the sign-in once the www page has loaded (or the wait for it was given up). */
    private void finishSignIn() {
        if (!establishingSession || !isAdded()) return;
        establishingSession = false;
        CookieManager.getInstance().flush();
        progress.setVisibility(View.GONE);
        Toast.makeText(requireContext(), R.string.gog_login_ok, Toast.LENGTH_SHORT).show();
        showSignedIn(true);
        status.setText("");
    }

    /**
     * List what the account owns that Zomdroid can install.
     *
     * <p>GOG's owned-games endpoint returns bare ids, so a title is only knowable by opening each
     * product: one request per game, which is why the scan reports its progress and stops at
     * {@link #SCAN_LIMIT}. Everything with a Linux installer is collected on the way through, so
     * that an account without the game still has something to show rather than an empty screen.
     */
    private void probeLibrary() {
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.gog_checking);
        clearCards();

        new Thread(() -> {
            List<GogLibrary.Product> zomboid = new ArrayList<>();
            List<GogLibrary.Product> anyLinux = new ArrayList<>();
            Throwable error = null;
            try {
                List<Long> owned = GogLibrary.ownedIds();
                int of = Math.min(owned.size(), SCAN_LIMIT);
                int scanned = 0;
                for (Long id : owned) {
                    if (scanned >= SCAN_LIMIT) break;
                    // Resolved on the UI side: a fragment the user has left has no resources.
                    final int at = ++scanned;
                    onUi(() -> status.setText(getString(R.string.gog_scanning, at, of)));

                    GogLibrary.Product p = GogLibrary.details(id);
                    if (p == null || p.linuxInstallers().isEmpty()) continue;
                    anyLinux.add(p);
                    if (isProjectZomboid(p.title)) zomboid.add(p);
                }
            } catch (Throwable t) {
                Log.e(TAG, "library scan failed", t);
                error = t;
            }

            final List<GogLibrary.Product> show = zomboid.isEmpty() ? anyLinux : zomboid;
            final boolean fellBack = zomboid.isEmpty() && !anyLinux.isEmpty();
            final Throwable err = error;
            if (err == null && !show.isEmpty()) {
                lastScan = show;
                lastScanFellBack = fellBack;
            }
            Log.i(TAG, "library scan: " + zomboid.size() + " zomboid, "
                    + anyLinux.size() + " with a linux installer");
            onUi(() -> {
                progress.setVisibility(View.GONE);
                if (err != null) {
                    status.setText(getString(R.string.gog_probe_failed,
                            String.valueOf(err.getMessage())));
                    return;
                }
                if (show.isEmpty()) { status.setText(R.string.gog_nothing_linux); return; }
                status.setText(fellBack ? getString(R.string.gog_no_game) : "");
                render(show);
            });
        }, "zd-gog-probe").start();
    }

    /** Lay out the cards: one per Linux installer of each game. */
    private void render(List<GogLibrary.Product> games) {
        clearCards();
        for (GogLibrary.Product game : games)
            for (GogLibrary.File f : oneLanguage(game.linuxInstallers()))
                addCard(game, f);
        refreshCards();
    }

    private void addCard(GogLibrary.Product game, GogLibrary.File f) {
        View row = getLayoutInflater().inflate(R.layout.item_gog_game, gameList, false);
        TextView titleView = row.findViewById(R.id.gog_item_title);
        titleView.setText(game.title == null || game.title.isEmpty() ? f.name : game.title);

        String version = f.version == null ? "" : f.version.trim();
        String details = version.isEmpty()
                ? f.size : getString(R.string.gog_item_installer, f.size, version);

        Card card = new Card(game, f, row.findViewById(R.id.gog_item_subtitle),
                row.findViewById(R.id.gog_item_button), details);
        card.button.setOnClickListener(x -> onCardButton(card));
        cards.add(card);
        gameList.addView(row);
    }

    private void clearCards() {
        cards.clear();
        gameList.removeAllViews();
    }

    @Override
    public void onQueueChanged() {
        if (getView() != null) refreshCards();
    }

    /** Bring every card's button and subtitle in line with the state of its file. */
    private void refreshCards() {
        File destDir = SteamGameDownloader.getDownloadsDir();
        GogDownloadQueue queue = GogDownloadQueue.get();
        for (Card c : cards) {
            GogDownloadQueue.Entry e = queue.entryFor(c.file, destDir);
            GogDownloadQueue.Phase phase = e == null ? null : e.phase;
            c.subtitle.setText(c.details);
            if (phase == GogDownloadQueue.Phase.QUEUED) {
                c.button.setText(R.string.gog_queued);
                c.button.setEnabled(false);
            } else if (phase == GogDownloadQueue.Phase.RUNNING) {
                c.button.setText(e.percent < 0
                        ? getString(R.string.gog_downloading)
                        : getString(R.string.gog_percent, e.percent));
                c.button.setEnabled(false);
            } else if (GogDownloader.completed(c.file, destDir) != null) {
                c.button.setText(R.string.gog_create_instance);
                // Only Project Zomboid goes further. Another game is listed when the account has
                // no Zomboid, so downloads can still be tried, but the installer would reject it.
                c.button.setEnabled(isProjectZomboid(c.game.title));
            } else {
                // Never asked for, downloaded and since deleted, or failed: offer it (again).
                if (phase == GogDownloadQueue.Phase.FAILED) c.subtitle.setText(failureText(e.failure));
                c.button.setText(R.string.gog_download);
                c.button.setEnabled(true);
            }
        }
    }

    /** A card's button: the next step for a file already on the phone, otherwise download it. */
    private void onCardButton(Card c) {
        File destDir = SteamGameDownloader.getDownloadsDir();
        File done = GogDownloader.completed(c.file, destDir);
        if (done != null) {
            if (!isProjectZomboid(c.game.title)) return;   // shut for other games, see refreshCards
            openNewInstance(done);
            return;
        }
        // The public Downloads/zomdroid folder is the point (the user can see and reuse the file,
        // next to the Steam downloads), and writing there needs All-files access on Android 11+.
        if (!ensureAllFilesAccess()) return;
        GogDownloadQueue.get().enqueue(requireContext(), c.file, destDir);
    }

    /**
     * Hand the installer to the new-instance screen by path. That screen's own picker is ZIP-only,
     * and most file managers would not show it a bare .sh anyway.
     */
    private void openNewInstance(File installer) {
        Bundle args = new Bundle();
        args.putString(NewGameInstanceFragment.ARG_PRESELECTED_FILE, installer.getAbsolutePath());
        NavHostFragment.findNavController(this).navigate(R.id.action_open_new_game_instance_fragment, args);
    }

    /**
     * Matches the game by title, loosely about spacing and suffixes: GOG's titles carry edition
     * names, and missing the game over one is worse than showing a card the user can ignore.
     */
    private static boolean isProjectZomboid(String title) {
        return title != null
                && title.toLowerCase(java.util.Locale.US).replace(" ", "").contains("zomboid");
    }

    /**
     * GOG lists the same installer once per language, which would put a dozen identical cards on
     * screen. Keep a single language, English when the account has it, otherwise whichever came
     * first, so that a multi-part installer still shows every one of its parts.
     */
    private static List<GogLibrary.File> oneLanguage(List<GogLibrary.File> installers) {
        String want = null;
        for (GogLibrary.File f : installers)
            if ("English".equalsIgnoreCase(f.language)) { want = f.language; break; }
        if (want == null && !installers.isEmpty()) want = installers.get(0).language;

        List<GogLibrary.File> out = new ArrayList<>();
        for (GogLibrary.File f : installers)
            if (want == null || want.equals(f.language)) out.add(f);
        return out;
    }

    /**
     * The line to show when a download stops early. A dropped connection gets its own message: it
     * is the everyday failure on a phone, and a recoverable one (what already arrived stays in the
     * part file for the next attempt), so the user is told to tap again rather than shown a socket
     * error. The exception itself is in the log either way. UI thread only.
     */
    private String failureText(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            boolean dropped = c instanceof java.net.SocketException           // incl. refused connects
                    || c instanceof java.net.SocketTimeoutException
                    || c instanceof java.net.UnknownHostException             // no network at all
                    || (c instanceof javax.net.ssl.SSLException
                        && !(c instanceof javax.net.ssl.SSLHandshakeException));
            if (dropped)
                return getString(R.string.gog_download_interrupted, getString(R.string.gog_download));
        }
        return getString(R.string.gog_download_failed, t == null ? "?" : String.valueOf(t.getMessage()));
    }

    // ---- All-files access: the same dialog the Steam screen shows, for the same folder ----
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

    /** Run {@code r} on the UI thread, or drop it if the screen is gone by the time it gets there. */
    private void onUi(Runnable r) {
        main.post(() -> { if (isAdded()) r.run(); });
    }

    @Override
    public void onDestroyView() {
        GogDownloadQueue.get().clearListener(this);
        cards.clear();
        if (web != null) {
            web.stopLoading();
            web.setWebViewClient(new WebViewClient());
            web.destroy();
            web = null;
        }
        super.onDestroyView();
    }
}
