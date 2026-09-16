package com.zomdroid.gog;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Reading a signed-in user's GOG library. Ported from RimDroid (MIT).
 *
 * <p>Every request here sends BOTH credentials {@link GogAuth} holds, the bearer token and the
 * cookies. GOG accepts token authorisation on all of its APIs and additionally accepts session
 * cookies on www.gog.com and embed.gog.com, so which one satisfies a given endpoint is not worth
 * predicting. Cookies alone earned a 403 ("Full authentication is required to access this
 * resource"), because the WebView login leaves galaxy-login-* cookies scoped to login.gog.com and
 * no www session at all.
 *
 * <p><b>On the response shape.</b> lgogdownloader parses nodes called {@code installers},
 * {@code patches} and {@code languagepacks} with a numeric {@code platform} bitmask. Those belong
 * to a structure lgogdownloader <i>builds for itself</i>, not to anything GOG sends; parsing the
 * real response that way silently yields zero files. What GOG actually returns is described on
 * {@link #details}: read it before changing this.
 *
 * <p>Not used on purpose: GOG Galaxy's content-system API (the chunked, depot-shaped one that
 * resembles the Steam downloader) serves {@code windows} and {@code osx} only. Linux builds live
 * exclusively on the older whole-file installer path used here.
 *
 * <p>Every method here blocks on network I/O: call from a worker thread.
 */
public final class GogLibrary {
    private static final String TAG = "Zomdroid/GOG";

    // Two different hosts on purpose. The owned-ids call is an API and lives on embed.gog.com,
    // which is where GOG documents it; gameDetails is an account *page* and is served from www.
    private static final String OWNED_URL   = "https://embed.gog.com/user/data/games";
    private static final String DETAILS_URL = "https://www.gog.com/account/gameDetails/";
    private static final String WWW         = "https://www.gog.com";

    /** DLC are nested product records; stop before a malformed reply can nest forever. */
    private static final int MAX_DLC_DEPTH = 2;

    private static volatile Context app;
    private static volatile String userAgent;

    private GogLibrary() {}

    /** Hand over a context once, before the first request: the user agent comes from the WebView. */
    public static void init(Context context) {
        app = context.getApplicationContext();
    }

    /**
     * The WebView's own User-Agent. GOG's edge refuses a default Android HTTP agent with 403 even
     * when the session is perfectly good, so every request here presents the same agent as the
     * page the user signed in on. Cached; it cannot change while the process lives.
     */
    private static String userAgent() {
        String ua = userAgent;
        if (ua != null) return ua;
        try {
            ua = android.webkit.WebSettings.getDefaultUserAgent(app);
        } catch (Throwable t) {
            Log.w(TAG, "no WebView user agent: " + t);
            ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";
        }
        userAgent = ua;
        return ua;
    }

    /**
     * A short, single-line extract of an error response. GOG and its edge normally say why they
     * refused; throwing that away turns every failure into another blind round of guessing.
     */
    private static String errorBody(HttpURLConnection conn) {
        try {
            String b = readAll(conn.getErrorStream()).trim().replaceAll("\\s+", " ");
            if (b.isEmpty()) return "(empty body)";
            return b.length() > 300 ? b.substring(0, 300) + "…" : b;
        } catch (Throwable t) {
            return "(body unreadable: " + t + ")";
        }
    }

    /** One downloadable file: an installer for some platform, or an extra. */
    public static final class File {
        public String name = "";
        /** A path on www.gog.com, e.g. {@code /downloads/game/en1installer0}. Resolve before use. */
        public String manualUrl = "";
        public String version = "";
        /** GOG reports a human string here ("2.1 GB"), not a byte count. */
        public String size = "";
        /** The language row this came from ("English"); empty for extras. */
        public String language = "";
        /** {@code windows} / {@code mac} / {@code linux}: the KEY it sat under. Empty for extras. */
        public String platform = "";
        /** {@code download} or {@code extra}. */
        public String kind = "";

        public boolean isLinux() { return "linux".equals(platform); }

        @Override public String toString() {
            return kind + " " + platform + " " + name + " [" + size + "]";
        }
    }

    /** A product in the library, with the files its page lists. */
    public static final class Product {
        public long id;
        public String title = "";
        public final List<File> files = new ArrayList<>();
        /** DLC arrive as nested product records, not as ids. */
        public final List<Product> dlcs = new ArrayList<>();

        /** The Linux installers, which is all Zomdroid can actually use. */
        public List<File> linuxInstallers() {
            List<File> out = new ArrayList<>();
            for (File f : files) if ("download".equals(f.kind) && f.isLinux()) out.add(f);
            return out;
        }
    }

    /** Product ids the signed-in account owns. */
    public static List<Long> ownedIds() throws IOException {
        JSONObject j = getJson(OWNED_URL);
        List<Long> out = new ArrayList<>();
        JSONArray owned = j.optJSONArray("owned");
        if (owned != null)
            for (int i = 0; i < owned.length(); i++) out.add(owned.optLong(i));
        Log.i(TAG, "library: " + out.size() + " products owned");
        return out;
    }

    /**
     * One product's page. Returns null when GOG has nothing for the id (it answers {@code []}).
     *
     * <p>The response is shaped like this: top level {@code title}, {@code downloads},
     * {@code extras}, {@code dlcs}, and nothing called "installers":
     * <pre>
     * { "title": "…",
     *   "downloads": [ [ "English", { "linux":   [ {name, manualUrl, version, size, date}, … ],
     *                                 "windows": [ … ] } ],
     *                  [ "Deutsch", { … } ] ],
     *   "extras":    [ {name, manualUrl, size}, … ],
     *   "dlcs":      [ { …the same shape again… } ] }
     * </pre>
     * So the platform is the KEY a file sits under, not a bitmask, and the link is
     * {@code manualUrl}, a path that {@link #resolveDownload} turns into a CDN URL.
     */
    public static Product details(long productId) throws IOException {
        JSONObject j = getJson(DETAILS_URL + productId + ".json");
        if (j.length() == 0) return null;
        // One line of truth per product: three rounds of this feature were lost to assuming a shape.
        Log.i(TAG, "product " + productId + " keys: " + keysOf(j));
        return parseProduct(productId, j, 0);
    }

    private static Product parseProduct(long id, JSONObject j, int depth) {
        Product p = new Product();
        p.id = id;
        p.title = j.optString("title", "");

        JSONArray downloads = j.optJSONArray("downloads");
        if (downloads != null) {
            for (int i = 0; i < downloads.length(); i++) {
                // Each row is [ language, { platform: [files] } ].
                JSONArray row = downloads.optJSONArray(i);
                if (row == null || row.length() < 2) continue;
                String language = row.optString(0, "");
                JSONObject byPlatform = row.optJSONObject(1);
                if (byPlatform == null) continue;
                for (Iterator<String> it = byPlatform.keys(); it.hasNext(); ) {
                    String platform = it.next();
                    JSONArray arr = byPlatform.optJSONArray(platform);
                    if (arr == null) continue;
                    for (int k = 0; k < arr.length(); k++) {
                        JSONObject e = arr.optJSONObject(k);
                        if (e == null) continue;
                        File f = readFile(e);
                        f.kind = "download";
                        f.platform = platform;
                        f.language = language;
                        p.files.add(f);
                    }
                }
            }
        }

        JSONArray extras = j.optJSONArray("extras");
        if (extras != null) {
            for (int i = 0; i < extras.length(); i++) {
                JSONObject e = extras.optJSONObject(i);
                if (e == null) continue;
                File f = readFile(e);
                f.kind = "extra";
                p.files.add(f);
            }
        }

        JSONArray dlcs = j.optJSONArray("dlcs");
        if (dlcs != null && depth < MAX_DLC_DEPTH) {
            for (int i = 0; i < dlcs.length(); i++) {
                JSONObject d = dlcs.optJSONObject(i);
                if (d != null) p.dlcs.add(parseProduct(d.optLong("id", 0L), d, depth + 1));
            }
        }

        Log.i(TAG, "product " + id + " '" + p.title + "': " + p.files.size() + " files, "
                + p.linuxInstallers().size() + " linux installers, " + p.dlcs.size() + " dlc");
        return p;
    }

    private static File readFile(JSONObject e) {
        File f = new File();
        f.name      = e.optString("name", "");
        f.manualUrl = e.optString("manualUrl", "");
        f.version   = e.optString("version", "");
        f.size      = e.optString("size", "");
        return f;
    }

    /**
     * Turn a file into the real CDN URL. {@code manualUrl} is a path on www.gog.com that answers
     * with a redirect to the signed CDN link, so ask without following it and read Location. That
     * link is short-lived and account-bound: use it now, do not store or log it.
     */
    public static String resolveDownload(File f) throws IOException {
        if (f.manualUrl == null || f.manualUrl.isEmpty())
            throw new IOException("file '" + f.name + "' carries no manualUrl");
        String url = f.manualUrl.startsWith("http") ? f.manualUrl : WWW + f.manualUrl;

        HttpURLConnection conn = open(url);
        try {
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code / 100 == 3) {
                String location = conn.getHeaderField("Location");
                if (location == null || location.isEmpty())
                    throw new IOException("GOG redirected '" + f.name + "' without a location");
                Log.i(TAG, "resolved '" + f.name + "' to " + hostOf(location));
                return location;
            }
            if (code == 200) return url;   // already the file itself
            throw new IOException("download link for '" + f.name + "' returned HTTP " + code
                    + ". Said: " + errorBody(conn));
        } finally {
            conn.disconnect();
        }
    }

    // --- plumbing ------------------------------------------------------------------------------

    /** GET a JSON document, presenting every credential held. */
    private static JSONObject getJson(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            int code = conn.getResponseCode();
            // GOG answers a signed-out request with a redirect to the login page rather than a 401,
            // so treat anything non-200 as "sign in again" instead of a hard failure.
            if (code != 200)
                throw new IOException("GOG returned HTTP " + code + " for " + url
                        + " - the session may have expired; sign in again. Said: " + errorBody(conn));
            String body = readAll(conn.getInputStream()).trim();
            if (body.isEmpty()) return new JSONObject();
            // A product the account cannot see comes back as an empty ARRAY. That is an answer,
            // not a failure; one such entry used to abort the whole library walk.
            if (body.startsWith("[")) return new JSONObject();
            try {
                return new JSONObject(body);
            } catch (Exception parse) {
                throw new IOException("GOG returned a non-JSON body for " + url + ": "
                        + body.substring(0, Math.min(120, body.length())));
            }
        } finally {
            conn.disconnect();
        }
    }

    /** An authorised GET, set up the way every call here needs it. */
    private static HttpURLConnection open(String url) throws IOException {
        String cookies = GogAuth.cookieHeader();
        String bearer = null;
        try {
            if (GogAuth.isSignedIn()) bearer = GogAuth.bearer();
        } catch (Exception e) {
            // A refresh failure is not fatal on its own: the cookies may still carry the request.
            Log.w(TAG, "could not obtain an access token: " + e);
        }
        // Only give up when nothing at all is held. Requiring cookies here is what hid the
        // token-only path during the 403 hunt.
        if (bearer == null && cookies == null)
            throw new IOException("not signed in to GOG");

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
        if (cookies != null) conn.setRequestProperty("Cookie", cookies);
        conn.setRequestProperty("User-Agent", userAgent());
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Referer", WWW + "/account");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        return conn;
    }

    private static String keysOf(JSONObject j) {
        StringBuilder sb = new StringBuilder();
        for (Iterator<String> it = j.keys(); it.hasNext(); ) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(it.next());
        }
        return sb.toString();
    }

    /** Host only: a resolved download link is a credential and does not belong in a log. */
    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "(unparseable host)";
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
