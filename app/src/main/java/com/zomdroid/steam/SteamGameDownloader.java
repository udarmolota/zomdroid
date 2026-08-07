package com.zomdroid.steam;

import android.os.Environment;
import android.util.Log;

import in.dragonbra.javasteam.enums.EDepotFileFlag;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;
import in.dragonbra.javasteam.steam.cdn.Client;
import in.dragonbra.javasteam.steam.cdn.Server;
import in.dragonbra.javasteam.steam.handlers.steamapps.License;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.DepotKeyCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSTokensCallback;
import in.dragonbra.javasteam.steam.handlers.steamcontent.CDNAuthToken;
import in.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
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
import in.dragonbra.javasteam.types.KeyValue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import kotlinx.coroutines.Deferred;
import kotlinx.coroutines.GlobalScope;

/**
 * In-app Steam downloader for the Project Zomboid <b>Linux</b> build, ported & adapted from RimDroid
 * (MIT, same author).
 *
 * <p>Uses the same LOW-MEMORY manual SteamPipe pipeline as the anonymous mod downloader (download one
 * chunk → decrypt/decompress → write → next) instead of JavaSteam's high-level DepotDownloader. The
 * high-level downloader pre-allocates all ~30k files and buffers chunks in parallel, which OOMs a
 * 256 MB app heap on a ~5 GB game; the manual loop keeps the footprint to a few MB.
 *
 * <p>Logs in (credentials → Steam Mobile approval push, or Steam Guard code) because the game depot
 * key requires ownership. Output is a plain folder under the public
 * Downloads/zomdroid/ProjectZomboid_&lt;build&gt; — no instance is created here.
 *
 * <p>Manifest: blank → the latest public build; a specific manifest id → exactly that build.
 * Refresh token kept ONLY in memory (never on disk).
 */
public class SteamGameDownloader implements Runnable, Cancellable {

    private static final String TAG = "Zomdroid/SteamDL";

    public static final int PROJECT_ZOMBOID_APP_ID = 108600;

    public interface Listener {
        CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email);
        void onProgress(String message);
        default void onPercent(int percent) {}
        void onDone(String message);
    }

    private final String username, password;
    private final long manifestId;       // 0 = latest build on the branch; >0 = pin this build
    private final String branch;         // Steam branch: "public" = Build 41, "unstable" = Build 42
    private final String buildLabel;     // "41" or "42" — for the output folder name only
    private final Listener listener;

    private SteamClient steamClient;
    private CallbackManager manager;
    private SteamUser steamUser;
    private List<License> licenseList;

    private volatile String accountName;
    private volatile String refreshToken;   // in-memory only

    private volatile boolean running;
    private volatile boolean started;
    private int connectAttempts = 0;
    private static final int MAX_CONNECT_ATTEMPTS = 5;
    private volatile Thread workerThread;   // the thread running download()
    private volatile boolean finished;      // ensure done() fires once

    public SteamGameDownloader(String username, String password, long manifestId,
                              String branch, String buildLabel, Listener listener) {
        this.username = username;
        this.password = password;
        this.manifestId = manifestId;
        this.branch = (branch != null && !branch.isEmpty()) ? branch : "public";
        this.buildLabel = (buildLabel != null && !buildLabel.isEmpty()) ? buildLabel : "41";
        this.listener = listener;
    }

    /** Public Downloads/zomdroid folder (created on demand). Requires All-files access on Android 11+. */
    public static File getDownloadsDir() {
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(dl, "zomdroid");
    }

    @Override
    public void cancel() {
        running = false;
        Thread w = workerThread;   // break the blocking chunk download / sleeps
        if (w != null) w.interrupt();
    }

    private void progress(String m) { Log.i(TAG, m); if (listener != null) listener.onProgress(m); }
    private void done(String m)     {
        if (finished) return;
        finished = true;
        Log.i(TAG, "DONE: " + m);
        if (listener != null) listener.onDone(m);
    }

    @Override
    public void run() {
        try {
            steamClient = new SteamClient();
            manager = new CallbackManager(steamClient);
            steamUser = steamClient.getHandler(SteamUser.class);
            manager.subscribe(ConnectedCallback.class, this::onConnected);
            manager.subscribe(DisconnectedCallback.class, this::onDisconnected);
            manager.subscribe(LoggedOnCallback.class, this::onLoggedOn);
            manager.subscribe(LicenseListCallback.class, this::onLicenseList);

            running = true;
            progress("Connecting to Steam...");
            steamClient.connect();
            while (running) manager.runWaitCallbacks(1000L);
            Log.i(TAG, "Download loop ended");
            // Safety net: never leave the UI stuck in "downloading" if the loop ended without
            // a terminal result.
            if (!finished) done("Stopped before finishing — please try again.");
        } catch (Throwable t) {
            if (running) {   // a real crash, not our cancel/stop
                Log.e(TAG, "SteamGameDownloader crashed", t);
                done("crash: " + t);
            } else {
                Log.i(TAG, "Callback loop stopped: " + t);
            }
        }
    }

    private void onConnected(ConnectedCallback cb) {
        try {
            if (refreshToken != null) { logOnWithToken(); return; }
            progress("Connected. Authenticating '" + username + "'...");
            AuthSessionDetails details = new AuthSessionDetails();
            details.username = username;
            details.password = password;
            details.persistentSession = false;          // ephemeral — no token stored on disk
            details.deviceFriendlyName = "Zomdroid";
            details.authenticator = new PushAuthenticator();

            CredentialsAuthSession session =
                    new SteamAuthentication(steamClient).beginAuthSessionViaCredentials(details).get();
            progress("Approve the sign-in in your Steam Mobile app...");
            AuthPollResult poll = session.pollingWaitForResult().get();
            accountName = poll.getAccountName();
            refreshToken = poll.getRefreshToken();       // memory only
            logOnWithToken();
        } catch (Throwable t) {
            Log.e(TAG, "Auth failed", t);
            done("auth error: " + describe(t));
            running = false;
        }
    }

    private void logOnWithToken() {
        LogOnDetails lod = new LogOnDetails();
        lod.setUsername(accountName);
        lod.setAccessToken(refreshToken);
        lod.setLoginID(149);
        progress("Logging in...");
        steamUser.logOn(lod);
    }

    private void onDisconnected(DisconnectedCallback cb) {
        if (!running) return;
        // Before the download starts, a disconnect means the connection/auth dropped — Steam often
        // drops the first connect attempt, so retry a few times before giving up.
        if (!started && connectAttempts < MAX_CONNECT_ATTEMPTS) {
            connectAttempts++;
            progress("Connection dropped — retrying (" + connectAttempts + "/" + MAX_CONNECT_ATTEMPTS + ")...");
            try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
            if (!running) return;   // cancelled while waiting to reconnect
            steamClient.connect();
            return;
        }
        if (!started) {
            done("Could not connect to Steam after " + connectAttempts
                    + " attempts. Check your connection and try again.");
        }
        running = false;
    }

    private void onLoggedOn(LoggedOnCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("logOn failed: " + cb.getResult());
            running = false;
            return;
        }
        progress("Logged on. Waiting for license list...");
    }

    private void onLicenseList(LicenseListCallback cb) {
        if (cb.getResult() != EResult.OK) {
            done("license list failed: " + cb.getResult());
            running = false;
            return;
        }
        licenseList = cb.getLicenseList();
        progress("Got " + (licenseList == null ? 0 : licenseList.size()) + " licenses.");
        if (started) return;
        started = true;
        workerThread = new Thread(this::download, "zd-game-dl");
        workerThread.start();
    }

    /** Resolved Linux depot id + manifest GID + build id from PICS. */
    private static final class DepotInfo { int depot = -1; long gid = 0L; long buildId = 0L; }

    private DepotInfo resolveLinuxDepot() {
        DepotInfo out = new DepotInfo();
        try {
            SteamApps apps = steamClient.getHandler(SteamApps.class);
            long appToken = 0L;
            try {
                PICSTokensCallback tk = apps.picsGetAccessTokens(
                                Collections.singletonList(PROJECT_ZOMBOID_APP_ID), Collections.<Integer>emptyList())
                        .toFuture().get(30, TimeUnit.SECONDS);
                Long t = tk.getAppTokens().get(PROJECT_ZOMBOID_APP_ID);
                if (t != null) appToken = t;
            } catch (Throwable ignored) {}

            AsyncJobMultiple.ResultSet<PICSProductInfoCallback> rs = apps.picsGetProductInfo(
                            Collections.singletonList(new PICSRequest(PROJECT_ZOMBOID_APP_ID, appToken)),
                            Collections.<PICSRequest>emptyList())
                    .toFuture().get(60, TimeUnit.SECONDS);
            for (PICSProductInfoCallback cb : rs.getResults()) {
                PICSProductInfo app = cb.getApps().get(PROJECT_ZOMBOID_APP_ID);
                if (app == null) continue;
                KeyValue depots = app.getKeyValues().get("depots");

                // Diagnostic only: Valve reshuffles branches without notice (e.g. the 2026-07-29
                // 42.20 release), and a branch we hardcode against can silently stop covering the
                // Linux depot. Logging every branch's buildid here means the next reshuffle shows
                // up in a tester's logcat instead of us guessing from SteamDB history.
                StringBuilder branchList = new StringBuilder();
                for (KeyValue b : depots.get("branches").getChildren()) {
                    if (branchList.length() > 0) branchList.append(", ");
                    branchList.append(b.getName()).append('=').append(b.get("buildid").asLong(0L));
                }
                Log.i(TAG, "Steam branches for Project Zomboid: " + branchList);

                out.buildId = depots.get("branches").get(branch).get("buildid").asLong(0L);
                for (KeyValue depot : depots.getChildren()) {
                    int depotId;
                    try { depotId = Integer.parseInt(depot.getName()); }
                    catch (NumberFormatException e) { continue; }     // skip "branches" etc.
                    String os = depot.get("config").get("oslist").asString();
                    if (os == null || !os.contains("linux")) continue;

                    // A manually pinned manifest (e.g. an old build id copied from SteamDB, for a
                    // version no branch currently points at — 42.15 and the like) needs only the
                    // depot id, found above by OS match alone. It must NOT require this branch to
                    // have its own manifest override for this depot: that requirement used to make
                    // a pinned manifest unusable whenever the selected branch itself didn't resolve
                    // (branch is still passed to the CDN request-code call further down, for
                    // authorization — just not consulted here to find the manifest gid).
                    if (manifestId > 0) {
                        out.depot = depotId;
                        out.gid = manifestId;
                        return out;
                    }

                    String gid = depot.get("manifests").get(branch).get("gid").asString();
                    if (gid == null || gid.isEmpty()) {
                        Log.i(TAG, "Linux depot " + depotId + " has no manifest override for branch '"
                                + branch + "'");
                        continue;
                    }
                    try {
                        out.depot = depotId;
                        out.gid = Long.parseUnsignedLong(gid);
                        return out;
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveLinuxDepot failed", t);
        }
        return out;
    }

    private void download() {
        try {
            progress("Resolving Linux depot...");
            DepotInfo info = resolveLinuxDepot();
            if (info.depot <= 0) { done("Could not find the Linux depot for Project Zomboid."); running = false; return; }

            long gid = manifestId > 0 ? manifestId : info.gid;
            if (gid == 0L) { done("Could not resolve a manifest to download."); running = false; return; }
            long buildId = manifestId > 0 ? manifestId : info.buildId;

            int appId = PROJECT_ZOMBOID_APP_ID;
            int depot = info.depot;
            progress("Depot " + depot + ", manifest " + gid + (buildId > 0 ? (", build " + buildId) : ""));

            byte[] depotKey = getDepotKey(depot, appId);
            if (depotKey == null) { done("No depot key (is the game owned on this account?)."); running = false; return; }

            File outDir = new File(getDownloadsDir(),
                    "ProjectZomboid_B" + buildLabel + "_" + (buildId > 0 ? buildId : "latest"));
            if (!outDir.exists() && !outDir.mkdirs()) {
                done("Cannot create download folder: " + outDir + " (grant All-files access?)");
                running = false; return;
            }

            SteamContent content = steamClient.getHandler(SteamContent.class);
            Client cdn = new Client(steamClient);

            List<Server> servers = awaitDeferred(
                    content.getServersForSteamPipe(null, null, GlobalScope.INSTANCE), 30000);
            if (servers == null || servers.isEmpty()) { done("No CDN servers available."); running = false; return; }

            long requestCode = awaitDeferred(
                    content.getManifestRequestCode(depot, appId, gid, branch, null, GlobalScope.INSTANCE), 30000);

            Map<String, String> tokenCache = new HashMap<>();
            DepotManifest manifest = null;
            Exception lastErr = null;
            int serverIdx = 0;
            for (int i = 0; i < servers.size(); i++) {
                Server s = servers.get(i);
                try {
                    manifest = cdn.downloadManifestFuture(depot, gid, requestCode, s, depotKey, null,
                            cdnTokenFor(content, appId, depot, s, tokenCache)).get(120, TimeUnit.SECONDS);
                    serverIdx = i;
                    break;
                } catch (Exception e) {
                    lastErr = e;
                    Log.w(TAG, "manifest via " + s.getHost() + " failed: " + describe(e));
                }
            }
            if (manifest == null) { done("Manifest download failed: " + describe(lastErr)); running = false; return; }

            List<FileData> files = manifest.getFiles();
            long totalBytes = 0;
            for (FileData f : files) {
                if (!f.getFlags().contains(EDepotFileFlag.Directory)) totalBytes += f.getTotalSize();
            }
            progress("Manifest OK: " + files.size() + " files, " + (totalBytes / (1024 * 1024)) + " MB. Downloading...");

            // Resume: skip files already fully downloaded in a previous run of this same build.
            File doneListFile = new File(outDir, ".zomdroid_complete");
            java.util.Set<String> doneSet = loadDoneSet(doneListFile);
            if (!doneSet.isEmpty()) progress("Resuming — " + doneSet.size() + " files already downloaded.");

            // Files are downloaded by a small pool rather than one at a time. The single-threaded loop
            // this replaces spent nearly all its wall clock waiting on CDN round-trips, not on disk or
            // CPU, so a handful of files in flight multiplies throughput without touching the reason
            // the manual pipeline exists: peak memory stays at one chunk buffer per worker (~1 MB
            // each) instead of JavaSteam's DepotDownloader buffering the whole depot.
            //
            // Parallelism is per FILE, not per chunk inside a file: the game is ~30k files and most
            // are a single chunk, so chunk-level splitting would leave the small ones serial anyway,
            // and per-file keeps the resume bookkeeping honest - a name reaches .zomdroid_complete
            // only after that whole file is written.
            final int workers = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
            final java.util.concurrent.atomic.AtomicLong doneBytes =
                    new java.util.concurrent.atomic.AtomicLong(0);
            final java.util.concurrent.atomic.AtomicInteger serverCursor =
                    new java.util.concurrent.atomic.AtomicInteger(serverIdx);
            final java.util.concurrent.atomic.AtomicInteger lastPct =
                    new java.util.concurrent.atomic.AtomicInteger(-1);
            final java.util.concurrent.atomic.AtomicLong lastEmit =
                    new java.util.concurrent.atomic.AtomicLong(0);
            final java.util.concurrent.atomic.AtomicReference<Throwable> firstError =
                    new java.util.concurrent.atomic.AtomicReference<>();

            final List<FileData> pending = new java.util.ArrayList<>();
            for (FileData f : files) {
                String rel = sanitizeRel(f.getFileName());
                if (rel == null) continue;
                File outFile = new File(outDir, rel);
                if (f.getFlags().contains(EDepotFileFlag.Directory)) { outFile.mkdirs(); continue; }
                // Already complete on a previous run -> skip (count toward progress).
                if (doneSet.contains(rel) && outFile.isFile() && outFile.length() == f.getTotalSize()) {
                    doneBytes.addAndGet(f.getTotalSize());
                    continue;
                }
                pending.add(f);
            }

            final long fTotalBytes = totalBytes;
            final int fDepot = depot;
            final byte[] fDepotKey = depotKey;
            final List<Server> fServers = servers;
            final Client fCdn = cdn;
            final SteamContent fContent = content;
            final Map<String, String> fTokenCache = tokenCache;
            final File fOutDir = outDir;
            final File fDoneListFile = doneListFile;

            final java.util.concurrent.ConcurrentLinkedQueue<FileData> queue =
                    new java.util.concurrent.ConcurrentLinkedQueue<>(pending);
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(workers);
            final List<Thread> pool = new java.util.ArrayList<>(workers);

            for (int w = 0; w < workers; w++) {
                Thread t = new Thread(() -> {
                    try {
                        FileData f;
                        while (running && firstError.get() == null && (f = queue.poll()) != null) {
                            try {
                                downloadOneFile(f, fOutDir, fDoneListFile, fCdn, fContent, fServers,
                                        fDepot, fDepotKey, fTokenCache, serverCursor,
                                        doneBytes, fTotalBytes, lastPct, lastEmit);
                            } catch (Throwable e) {
                                firstError.compareAndSet(null, e);
                                return;
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }, "zd-game-dl-" + w);
                t.setDaemon(true);
                pool.add(t);
                t.start();
            }

            try {
                latch.await();
            } catch (InterruptedException cancelled) {
                // cancel() interrupts the download thread; the workers see running=false and unwind
                // on their own. Interrupt them too so anyone parked in a backoff sleep leaves now
                // instead of after its remaining 10 seconds.
                running = false;
                for (Thread t : pool) t.interrupt();
                done("Download cancelled.");
                return;
            }

            if (!running) { done("Aborted."); return; }
            Throwable workerError = firstError.get();
            if (workerError != null) throw workerError;

            done("Game downloaded to " + fOutDir.getAbsolutePath());
        } catch (Throwable t) {
            if (!running) {
                done("Download cancelled.");
            } else {
                Log.e(TAG, "download crashed", t);
                done("download error: " + describe(t));
            }
        } finally {
            running = false;
            try { steamUser.logOff(); } catch (Throwable ignored) {}
        }
    }

    private byte[] getDepotKey(int depot, int appId) {
        try {
            SteamApps apps = steamClient.getHandler(SteamApps.class);
            DepotKeyCallback dk = apps.getDepotDecryptionKey(depot, appId).toFuture().get(30, TimeUnit.SECONDS);
            if (dk.getResult() == EResult.OK && dk.getDepotKey() != null && dk.getDepotKey().length == 32) {
                return dk.getDepotKey();
            }
            Log.w(TAG, "depot key result=" + dk.getResult());
        } catch (Throwable t) {
            Log.e(TAG, "getDepotKey failed", t);
        }
        return null;
    }

    /**
     * Download one file's chunks and write them at their offsets. Runs on a pool thread; everything
     * shared with the other workers is passed in as an atomic or guarded internally.
     */
    private void downloadOneFile(FileData f, File outDir, File doneListFile,
                                 Client cdn, SteamContent content, List<Server> servers,
                                 int depot, byte[] depotKey, Map<String, String> tokenCache,
                                 java.util.concurrent.atomic.AtomicInteger serverCursor,
                                 java.util.concurrent.atomic.AtomicLong doneBytes, long totalBytes,
                                 java.util.concurrent.atomic.AtomicInteger lastPct,
                                 java.util.concurrent.atomic.AtomicLong lastEmit) throws Exception {
        String rel = sanitizeRel(f.getFileName());
        if (rel == null) return;
        File outFile = new File(outDir, rel);
        File parent = outFile.getParentFile();
        if (parent != null) parent.mkdirs();

        try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw")) {
            if (f.getTotalSize() > 0) raf.setLength(f.getTotalSize());
            for (ChunkData chunk : f.getChunks()) {
                if (!running) return;
                byte[] dest = new byte[Math.max(chunk.getCompressedLength(), chunk.getUncompressedLength())];
                int written = -1;
                Exception chunkErr = null;
                // Steam CDN edge nodes routinely 503/timeout under load. Retry patiently with
                // exponential backoff + server rotation instead of aborting the whole download.
                final int maxTries = 30;
                for (int t = 0; t < maxTries && written < 0; t++) {
                    if (!running) return;
                    // Round-robin, not sticky: the single-threaded version stayed on one healthy
                    // server and only moved after a failure, which with several workers would point
                    // all of them at the same edge node - the very thing that makes those nodes
                    // start returning 503. Advancing per attempt spreads the load and also lands a
                    // retry somewhere else by construction.
                    Server s = servers.get(Math.floorMod(serverCursor.getAndIncrement(), servers.size()));
                    try {
                        written = cdn.downloadDepotChunkFuture(depot, chunk, s, dest, depotKey, null,
                                cdnTokenFor(content, PROJECT_ZOMBOID_APP_ID, depot, s, tokenCache))
                                .get(120, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        chunkErr = e;
                        Log.w(TAG, "chunk via " + s.getHost() + " failed (try " + (t + 1) + "/" + maxTries + "): " + describe(e));
                        long now = System.currentTimeMillis();
                        long prev = lastEmit.get();
                        if (now - prev > 1500 && lastEmit.compareAndSet(prev, now) && listener != null) {
                            listener.onProgress("Steam CDN busy — retrying… ("
                                    + (doneBytes.get() / (1024 * 1024)) + " / " + (totalBytes / (1024 * 1024)) + " MB)");
                        }
                        long backoff = Math.min(10000L, 500L * (1L << Math.min(t, 4))); // 0.5..10s
                        try { Thread.sleep(backoff); } catch (InterruptedException ignored) { return; }
                    }
                }
                if (written < 0) throw chunkErr != null ? chunkErr : new java.io.IOException("chunk download failed");
                raf.seek(chunk.getOffset());
                raf.write(dest, 0, written);
                long total = doneBytes.addAndGet(written);

                long now = System.currentTimeMillis();
                int pct = totalBytes > 0 ? (int) (total * 100 / totalBytes) : 0;
                long prev = lastEmit.get();
                if (pct != lastPct.get() && now - prev > 500 && lastEmit.compareAndSet(prev, now)) {
                    lastPct.set(pct);
                    progress(pct + "%  (" + (total / (1024 * 1024)) + " / " + (totalBytes / (1024 * 1024)) + " MB)");
                    if (listener != null) listener.onPercent(pct);
                }
            }
        }
        // File fully written — record it so a restart of this build can skip it. Only reached when
        // every chunk landed, so a half-written file is never marked complete.
        if (running) appendDone(doneListFile, rel);
    }

    private String cdnTokenFor(SteamContent content, int appId, int depot, Server s, Map<String, String> cache) {
        String host = s.getHost() != null ? s.getHost() : s.getVHost();
        if (host == null) return null;
        // Guarded rather than a ConcurrentHashMap because a null token is a real cached answer and
        // that map forbids null values. Holding the lock across the fetch is deliberate: the workers
        // all want the same host's token at the same moment, and one request beats six identical ones.
        synchronized (cache) {
            if (cache.containsKey(host)) return cache.get(host);
            String token = null;
            try {
                CDNAuthToken tok = awaitDeferred(content.getCDNAuthToken(appId, depot, host, GlobalScope.INSTANCE), 15000);
                if (tok != null && tok.getResult() == EResult.OK) token = tok.getToken();
            } catch (Throwable ignored) {}
            cache.put(host, token);
            return token;
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

    /** Reads the set of already-completed relative file paths from the resume marker. */
    private static java.util.Set<String> loadDoneSet(File f) {
        java.util.Set<String> s = new java.util.HashSet<>();
        if (f == null || !f.isFile()) return s;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) s.add(line);
            }
        } catch (Exception ignored) {}
        return s;
    }

    /** Appends one completed relative path to the resume marker (flushed immediately). */
    // synchronized: several download workers finish files at once and all append to this one list.
    private static synchronized void appendDone(File f, String rel) {
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(rel);
            w.write('\n');
        } catch (Exception ignored) {}
    }

    private static String sanitizeRel(String name) {
        if (name == null) return null;
        String rel = name.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty() || rel.contains("../")) return null;
        return rel;
    }

    private static String describe(Throwable t) {
        if (t == null) return "null";
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        if (c != null && c != t) {
            sb.append("  <- ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
        }
        return sb.toString();
    }

    /** Approves via Steam Mobile push when possible; otherwise asks the UI for a Steam Guard code. */
    private class PushAuthenticator implements IAuthenticator {
        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
        @Override
        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            return listener != null
                    ? listener.requestSteamGuardCode(previousCodeWasIncorrect, null)
                    : CompletableFuture.completedFuture("");
        }
        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            return listener != null
                    ? listener.requestSteamGuardCode(previousCodeWasIncorrect, email)
                    : CompletableFuture.completedFuture("");
        }
    }
}
