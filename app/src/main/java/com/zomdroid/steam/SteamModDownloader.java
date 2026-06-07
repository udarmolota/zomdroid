/*
 * SPDX-License-Identifier: MIT
 *
 * Anonymous Steam Workshop mod downloader — original mechanism by udarmolota.
 * Copyright (c) 2026 udarmolota
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following condition: the above copyright notice and this
 * permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.zomdroid.steam;

import android.util.Log;

import com.zomdroid.AppStorage;
import com.zomdroid.ZipUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import in.dragonbra.javasteam.enums.EDepotFileFlag;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.cdn.Client;
import in.dragonbra.javasteam.steam.cdn.Server;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.DepotKeyCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSTokensCallback;
import in.dragonbra.javasteam.steam.handlers.steamcontent.CDNAuthToken;
import in.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.types.AsyncJobMultiple;
import in.dragonbra.javasteam.types.ChunkData;
import in.dragonbra.javasteam.types.DepotManifest;
import in.dragonbra.javasteam.types.FileData;
import in.dragonbra.javasteam.util.log.DefaultLogListener;
import in.dragonbra.javasteam.util.log.LogManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import kotlinx.coroutines.Deferred;
import kotlinx.coroutines.GlobalScope;

/**
 * ANONYMOUS in-app Workshop mod downloader (no Steam login), ported to Zomdroid. An anonymous account
 * CAN obtain the workshop depot key for public items.
 *
 * App-agnostic: reads the mod's owning game from the item (GetPublishedFileDetails → consumer_app_id),
 * so it downloads any public Workshop item. Items requiring ownership return EResult != OK on the depot
 * key and are skipped.
 *
 * Manual SteamPipe download (resolve workshop depot via PICS → depot key → CDN server → manifest request
 * code → download/decrypt manifest → download/decrypt/decompress each chunk), reusing JavaSteam's CDN
 * {@link Client} + crypto. (DepotDownloader's high-level processPublishedFile has a file-type-gate bug
 * that skips Community items, hence the manual path.)
 *
 * Output: each item packed into Downloads/zomdroid/&lt;Mod Title&gt;_&lt;id&gt;.zip.
 * Run on a background thread.
 */
public class SteamModDownloader implements Runnable, Cancellable {

    private static final String TAG = "Zomdroid/AnonMod";

    public interface Listener {
        void onProgress(String message);
        default void onPercent(int percent) {}
        void onDone(String message);
    }

    private static final class PubInfo {
        int consumerAppId;
        long hcontentFile;
        String title = "(workshop item)";
        boolean ok;
    }

    private final List<Long> workshopIds;
    private final Listener listener;

    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;
    private volatile boolean running;
    private volatile String lastTitle;
    private volatile Thread workerThread;   // the thread running downloadAll()
    private volatile boolean finished;      // ensure done() fires once

    public SteamModDownloader(List<Long> workshopIds, Listener listener) {
        this.workshopIds = workshopIds;
        this.listener = listener;
    }

    @Override
    public void cancel() {
        running = false;
        Thread w = workerThread;
        if (w != null) w.interrupt();
    }

    private void progress(String m) { Log.i(TAG, m); if (listener != null) listener.onProgress(m); }
    private void done(String m)     {
        if (finished) return;
        finished = true;
        Log.i(TAG, "DONE: " + m);
        if (listener != null) listener.onDone(m);
    }
    private void percent(int p)     { if (listener != null) listener.onPercent(p); }

    @Override
    public void run() {
        try {
            steamClient = new SteamClient();
            manager = new CallbackManager(steamClient);
            steamUser = steamClient.getHandler(SteamUser.class);
            manager.subscribe(ConnectedCallback.class, this::onConnected);
            manager.subscribe(DisconnectedCallback.class, this::onDisconnected);
            manager.subscribe(LoggedOnCallback.class, this::onLoggedOn);
            running = true;
            progress("Connecting to Steam (anonymous)...");
            steamClient.connect();
            while (running) manager.runWaitCallbacks(1000L);
            Log.i(TAG, "Anon downloader loop ended");
        } catch (Throwable t) {
            if (running) {
                Log.e(TAG, "SteamModDownloader crashed", t);
                done("crash: " + t);
            } else {
                Log.i(TAG, "Callback loop stopped: " + t);
            }
        }
    }

    private void onConnected(ConnectedCallback cb) {
        progress("Connected. Logging on anonymously (no account)...");
        steamUser.logOnAnonymous();
    }

    private void onDisconnected(DisconnectedCallback cb) {
        if (running) progress("Disconnected.");
        running = false;
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("Anonymous logon failed: " + cb.getResult());
            running = false;
            return;
        }
        workerThread = new Thread(this::downloadAll, "zd-anon-mod-dl");
        workerThread.start();
    }

    private void downloadAll() {
        File downloadsDir = SteamGameDownloader.getDownloadsDir();
        File tmpRoot = new File(AppStorage.requireSingleton().getCachePath(), "anon_mod_work");
        Client cdn = new Client(steamClient);
        int ok = 0, skipped = 0;
        try {
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                done("Cannot create downloads folder: " + downloadsDir + " (grant All-files access?)");
                return;
            }
            for (Long id : workshopIds) {
                if (!running) { progress("Aborted (disconnected)."); break; }
                progress("=== Workshop item " + id + " (anonymous) ===");
                File work = new File(tmpRoot, String.valueOf(id));
                deleteRecursive(work);
                if (!work.mkdirs()) { progress("✗ " + id + ": cannot create work dir"); skipped++; continue; }

                try {
                    if (downloadOne(cdn, id, work) && hasContent(work)) {
                        File zip = new File(downloadsDir, sanitizeFileName(lastTitle) + "_" + id + ".zip");
                        try (FileOutputStream fos = new FileOutputStream(zip)) {
                            ZipUtils.zipDirectoryToStream(work, fos);
                        }
                        progress("✓ " + id + " → " + zip.getAbsolutePath());
                        ok++;
                    } else {
                        progress("✗ " + id + " — nothing usable downloaded "
                                + "(items that require game ownership can't be downloaded anonymously).");
                        skipped++;
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "item " + id + " failed", t);
                    progress("✗ " + id + " — " + describe(t));
                    skipped++;
                }
                deleteRecursive(work);
            }
            done("Mods done: " + ok + " downloaded, " + skipped + " skipped. Saved to " + downloadsDir);
        } catch (Throwable t) {
            Log.e(TAG, "downloadAll crashed", t);
            done("error: " + describe(t));
        } finally {
            running = false;
            try { steamUser.logOff(); } catch (Throwable ignored) {}
        }
    }

    private boolean downloadOne(Client cdn, long id, File work) throws Exception {
        PubInfo info = resolvePublishedFile(id);
        lastTitle = info.title;
        if (!info.ok || info.consumerAppId <= 0 || info.hcontentFile == 0) {
            progress("✗ " + id + ": not found / no content manifest.");
            return false;
        }
        int appId = info.consumerAppId;
        progress("item '" + info.title + "' → app " + appId + ", manifest " + info.hcontentFile);

        int depot = resolveWorkshopDepot(appId);
        if (depot <= 0) { progress("✗ could not resolve workshop depot for app " + appId); return false; }

        byte[] depotKey = getDepotKey(depot, appId);
        if (depotKey == null) {
            progress("✗ no depot key (item likely needs game ownership).");
            return false;
        }

        SteamContent content = steamClient.getHandler(SteamContent.class);
        List<Server> servers = awaitDeferred(
                content.getServersForSteamPipe(null, null, GlobalScope.INSTANCE), 30000);
        if (servers == null || servers.isEmpty()) { progress("✗ no CDN servers"); return false; }

        long requestCode = awaitDeferred(
                content.getManifestRequestCode(depot, appId, info.hcontentFile, "public", null,
                        GlobalScope.INSTANCE), 30000);

        DepotManifest manifest = null;
        Exception lastErr = null;
        Map<String, String> tokenCache = new HashMap<>();
        int firstGood = -1;
        for (int i = 0; i < servers.size(); i++) {
            Server s = servers.get(i);
            try {
                manifest = cdn.downloadManifestFuture(depot, info.hcontentFile, requestCode, s,
                        depotKey, null, cdnTokenFor(content, appId, depot, s, tokenCache))
                        .get(90, TimeUnit.SECONDS);
                firstGood = i;
                break;
            } catch (Exception e) {
                lastErr = e;
                Log.w(TAG, "manifest via " + s.getHost() + " failed: " + describe(e));
            }
        }
        if (manifest == null) {
            progress("✗ manifest download failed: " + describe(lastErr));
            return false;
        }

        List<FileData> files = manifest.getFiles();
        long totalBytes = 0;
        for (FileData f : files) {
            if (!f.getFlags().contains(EDepotFileFlag.Directory)) totalBytes += f.getTotalSize();
        }
        progress("manifest OK: " + files.size() + " entries, "
                + (totalBytes / (1024 * 1024)) + " MB — downloading...");

        int serverIdx = Math.max(firstGood, 0);
        long doneBytes = 0;
        int lastPct = -1;
        for (FileData f : files) {
            if (!running) return false;
            String rel = sanitizeRel(f.getFileName());
            if (rel == null) continue;
            File out = new File(work, rel);
            if (f.getFlags().contains(EDepotFileFlag.Directory)) {
                //noinspection ResultOfMethodCallIgnored
                out.mkdirs();
                continue;
            }
            File parent = out.getParentFile();
            if (parent != null) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();

            try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
                if (f.getTotalSize() > 0) raf.setLength(f.getTotalSize());
                for (ChunkData chunk : f.getChunks()) {
                    if (!running) return false;
                    byte[] dest = new byte[Math.max(chunk.getCompressedLength(), chunk.getUncompressedLength())];
                    int written = -1;
                    Exception chunkErr = null;
                    int tries = Math.min(Math.max(servers.size(), 4), 8);
                    for (int t = 0; t < tries && written < 0; t++) {
                        Server s = servers.get(serverIdx % servers.size());
                        try {
                            written = cdn.downloadDepotChunkFuture(depot, chunk, s, dest, depotKey, null,
                                    cdnTokenFor(content, appId, depot, s, tokenCache)).get(180, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            chunkErr = e;
                            Log.w(TAG, "chunk via " + s.getHost() + " failed (try " + (t + 1) + "): " + describe(e));
                            serverIdx++;
                            Thread.sleep(400);
                        }
                    }
                    if (written < 0) {
                        throw chunkErr != null ? chunkErr : new java.io.IOException("chunk download failed");
                    }
                    raf.seek(chunk.getOffset());
                    raf.write(dest, 0, written);
                    doneBytes += written;
                    int pct = totalBytes > 0 ? (int) (doneBytes * 100 / totalBytes) : 0;
                    if (pct != lastPct) { percent(pct); lastPct = pct; }
                }
            }
        }
        progress("content fetched (" + (doneBytes / (1024 * 1024)) + " MB)");
        return true;
    }

    private String cdnTokenFor(SteamContent content, int appId, int depot, Server s, Map<String, String> cache) {
        String host = s.getHost() != null ? s.getHost() : s.getVHost();
        if (host == null) return null;
        if (cache.containsKey(host)) return cache.get(host);
        String token = null;
        try {
            CDNAuthToken tok = awaitDeferred(
                    content.getCDNAuthToken(appId, depot, host, GlobalScope.INSTANCE), 15000);
            if (tok != null && tok.getResult() == EResult.OK) token = tok.getToken();
        } catch (Throwable ignored) {}
        cache.put(host, token);
        return token;
    }

    private byte[] getDepotKey(int depot, int appId) {
        try {
            SteamApps apps = steamClient.getHandler(SteamApps.class);
            DepotKeyCallback dk = apps.getDepotDecryptionKey(depot, appId)
                    .toFuture().get(30, TimeUnit.SECONDS);
            if (dk.getResult() == EResult.OK && dk.getDepotKey() != null && dk.getDepotKey().length == 32) {
                return dk.getDepotKey();
            }
            Log.w(TAG, "depot key result=" + dk.getResult());
        } catch (Throwable t) {
            Log.e(TAG, "getDepotKey failed", t);
        }
        return null;
    }

    private int resolveWorkshopDepot(int appId) {
        try {
            SteamApps apps = steamClient.getHandler(SteamApps.class);
            long token = 0L;
            try {
                PICSTokensCallback tk = apps.picsGetAccessTokens(
                        Collections.singletonList(appId), Collections.<Integer>emptyList())
                        .toFuture().get(30, TimeUnit.SECONDS);
                Long t = tk.getAppTokens().get(appId);
                if (t != null) token = t;
            } catch (Throwable ignored) {}
            List<PICSRequest> reqs = Collections.singletonList(new PICSRequest(appId, token));
            AsyncJobMultiple.ResultSet<PICSProductInfoCallback> rs =
                    apps.picsGetProductInfo(reqs, Collections.<PICSRequest>emptyList())
                        .toFuture().get(60, TimeUnit.SECONDS);
            for (PICSProductInfoCallback cb : rs.getResults()) {
                PICSProductInfo app = cb.getApps().get(appId);
                if (app != null) {
                    return app.getKeyValues().get("depots").get("workshopdepot").asInteger(-1);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "resolveWorkshopDepot failed", t);
        }
        return -1;
    }

    private PubInfo resolvePublishedFile(long id) throws Exception {
        URL url = new URL("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            String body = "itemcount=1&publishedfileids%5B0%5D=" + id;
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
            }
            PubInfo pf = new PubInfo();
            JSONObject root = new JSONObject(sb.toString());
            JSONObject resp = root.optJSONObject("response");
            if (resp == null) return pf;
            JSONArray arr = resp.optJSONArray("publishedfiledetails");
            if (arr == null || arr.length() == 0) return pf;
            JSONObject d = arr.getJSONObject(0);
            pf.ok = d.optInt("result", 0) == 1;
            pf.consumerAppId = (int) d.optLong("consumer_app_id", 0L);
            pf.title = d.optString("title", "(workshop item)");
            String h = d.optString("hcontent_file", "");
            if (!h.isEmpty()) {
                try { pf.hcontentFile = Long.parseUnsignedLong(h); } catch (NumberFormatException ignored) {}
            }
            return pf;
        } finally {
            c.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T awaitDeferred(Deferred<T> d, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!d.isCompleted()) {
            if (System.currentTimeMillis() > deadline) throw new TimeoutException("deferred timed out");
            Thread.sleep(40);
        }
        return (T) d.getCompleted();
    }

    private static String sanitizeRel(String name) {
        if (name == null) return null;
        String rel = name.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty() || rel.contains("../")) return null;
        return rel;
    }

    private static String sanitizeFileName(String title) {
        if (title == null) return "workshop";
        String s = title.replaceAll("[\\\\/:*?\"<>|]", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
        if (s.length() > 60) s = s.substring(0, 60).trim();
        return s.isEmpty() ? "workshop" : s;
    }

    /** Proof a real item was downloaded: any non-empty regular file present. */
    private static boolean hasContent(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return false;
        for (File f : kids) {
            if (f.isDirectory()) {
                if (hasContent(f)) return true;
            } else if (f.length() > 0) {
                return true;
            }
        }
        return false;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String describe(Throwable t) {
        if (t == null) return "null";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m != null ? (": " + m) : "");
    }
}
