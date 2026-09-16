package com.zomdroid.gog;

import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * GOG sign-in for the in-app downloader. Ported from RimDroid (MIT), where the flow was worked out
 * on device.
 *
 * <p>GOG needs TWO credentials at once, which is the one surprise in this API:
 * <ul>
 *   <li>the <b>cookies</b> from the web login authorise the library listing
 *       ({@code /user/data/games}, {@code /account/gameDetails/&lt;id&gt;.json});</li>
 *   <li>the <b>bearer token</b> authorises resolving a file's download link into the real CDN link.</li>
 * </ul>
 * So the OAuth code flow runs in a WebView ({@link com.zomdroid.fragments.GogDownloadFragment}):
 * the WebView leaves the cookies in Android's {@link CookieManager}, and the authorisation code it
 * lands on is exchanged here for the tokens.
 *
 * <p>A WebView is not a convenience: the login page can present a CAPTCHA, an e-mail two-step
 * check or a TOTP prompt, none of which a headless POST can clear.
 *
 * <p>What the WebView cannot do is finish a <b>linked-account</b> sign-in. Tried with Google: the
 * flow leaves the WebView for Play Services' own account activity, that activity closes, and
 * nothing comes back. Only Google was measured; the on-screen note speaks of linked accounts in
 * general and points people at a GOG password.
 *
 * <p><b>What is kept:</b> the tokens live in memory only and die with the process, matching the
 * Steam path. The cookies, however, are held by Android's own cookie store for this app, so the
 * sign-in survives a restart until {@link #signOut()} clears it. The user-facing note says exactly
 * that; do not copy Steam's "nothing is stored" wording.
 */
public final class GogAuth {
    private static final String TAG = "Zomdroid/GOG";

    // GOG Galaxy's own client credentials. They are fixed, public knowledge (every third-party GOG
    // client uses them) rather than a granted API key. GOG can rotate them, which would show up as
    // a sudden 400 from the token endpoint.
    private static final String CLIENT_ID     = "46899977096215655";
    private static final String CLIENT_SECRET = "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9";

    /** Galaxy's own redirect target. It is never loaded: catching the navigation IS the callback. */
    public static final String REDIRECT_URI = "https://embed.gog.com/on_login_success?origin=client";

    private static final String AUTH_URL  = "https://auth.gog.com/auth";
    private static final String TOKEN_URL = "https://auth.gog.com/token";

    /** Refresh this long before the token actually expires, so a slow request cannot straddle it. */
    private static final long REFRESH_MARGIN_MS = 60_000L;

    private static String accessToken;
    private static String refreshToken;
    private static String userId;
    private static long   expiresAtMs;

    private GogAuth() {}

    /** The page to load in the WebView to begin a sign-in. */
    public static String authUrl() {
        return AUTH_URL
                + "?client_id="    + enc(CLIENT_ID)
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&response_type=code"
                + "&layout=client2";
    }

    /**
     * The authorisation code, if this URL is the post-login redirect. The WebView hands over every
     * navigation; this returns null for all of them except the one that carries {@code ?code=}.
     */
    public static String codeFrom(String url) {
        if (url == null || !url.startsWith("https://embed.gog.com/on_login_success")) return null;
        try {
            String code = Uri.parse(url).getQueryParameter("code");
            return (code == null || code.isEmpty()) ? null : code;
        } catch (Exception e) {
            Log.w(TAG, "could not parse redirect: " + e);
            return null;
        }
    }

    /** Exchange the code for tokens. Blocking: call off the UI thread. */
    public static synchronized void signInWithCode(String code) throws Exception {
        store(token("grant_type=authorization_code"
                + "&code="         + enc(code)
                + "&redirect_uri=" + enc(REDIRECT_URI)));
        Log.i(TAG, "signed in, user " + userId);
    }

    public static synchronized boolean isSignedIn() {
        return accessToken != null;
    }

    /** GOG's own id for the signed-in user, or null. For the screen and the log; never sent anywhere else. */
    public static synchronized String userId() {
        return userId;
    }

    /**
     * A valid access token, refreshing first if it is about to expire. Blocking: call off the UI
     * thread.
     */
    public static synchronized String bearer() throws Exception {
        if (accessToken == null) throw new IllegalStateException("not signed in");
        if (System.currentTimeMillis() >= expiresAtMs - REFRESH_MARGIN_MS) {
            Log.i(TAG, "access token expiring - refreshing");
            store(token("grant_type=refresh_token&refresh_token=" + enc(refreshToken)));
        }
        return accessToken;
    }

    /**
     * The {@code Cookie:} header for gog.com, straight from the WebView's store. Null when the user
     * has never signed in, or when Android has since dropped the cookies: treat that as "signed
     * out" and send them through the login screen again rather than failing the download silently.
     */
    public static String cookieHeader() {
        try {
            CookieManager cm = CookieManager.getInstance();
            // Merge, do not pick one host. The OAuth flow walks auth -> login -> embed and never
            // visits www, so on a fresh sign-in the only cookies held are galaxy-login-al and
            // galaxy-login-s on login.gog.com, while the listing endpoints live on www. A browser
            // would refuse to send a login.gog.com cookie to www; a plain HTTP client may, and GOG
            // treats that pair as the account session. Once the fragment has loaded www.gog.com
            // once, the proper www cookies exist and win here by being seen first.
            java.util.LinkedHashMap<String, String> jar = new java.util.LinkedHashMap<>();
            for (String host : new String[]{
                    "https://www.gog.com", "https://gog.com",
                    "https://login.gog.com", "https://embed.gog.com" }) {
                String line = cm.getCookie(host);
                if (line == null || line.isEmpty()) continue;
                for (String pair : line.split(";")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    String name = pair.substring(0, eq).trim();
                    // First host wins: www's own session must not be overwritten by a stale
                    // login-host copy of the same name.
                    if (!name.isEmpty() && !jar.containsKey(name))
                        jar.put(name, pair.trim());
                }
            }
            if (jar.isEmpty()) return null;
            return String.join("; ", jar.values());
        } catch (Throwable t) {          // CookieManager throws if no WebView is available at all
            Log.w(TAG, "cookie store unavailable: " + t);
            return null;
        }
    }

    /** Forget the tokens AND clear the cookies; this is what makes "sign out" honest. */
    public static synchronized void signOut() {
        accessToken = refreshToken = userId = null;
        expiresAtMs = 0;
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Throwable t) {
            Log.w(TAG, "could not clear cookies: " + t);
        }
        Log.i(TAG, "signed out");
    }

    // --- plumbing ------------------------------------------------------------------------------

    /** One call to the token endpoint. {@code grantParams} carries grant_type and its arguments. */
    private static JSONObject token(String grantParams) throws Exception {
        String url = TOKEN_URL
                + "?client_id="     + enc(CLIENT_ID)
                + "&client_secret=" + enc(CLIENT_SECRET)
                + "&" + grantParams;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code != 200) {
                // 400 here after a working build usually means GOG rotated the Galaxy client
                // credentials above, not that the user typed something wrong.
                throw new java.io.IOException("token endpoint returned HTTP " + code
                        + ": " + readAll(conn.getErrorStream()));
            }
            return new JSONObject(readAll(conn.getInputStream()));
        } finally {
            conn.disconnect();
        }
    }

    private static void store(JSONObject j) throws Exception {
        accessToken  = j.getString("access_token");
        refreshToken = j.getString("refresh_token");
        userId       = j.optString("user_id", userId);
        // expires_in is seconds, about an hour.
        expiresAtMs  = System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000L;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;   // the values encoded here are ASCII; this cannot realistically fire
        }
    }
}
