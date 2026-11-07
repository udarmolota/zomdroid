package com.zomdroid.steam;

import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import in.dragonbra.javasteam.steam.cdn.Server;
import in.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent;
import kotlinx.coroutines.Deferred;
import kotlinx.coroutines.GlobalScope;

/**
 * The Steam CDN servers a download uses. Steam answers getServersForSteamPipe() with the servers of
 * the "cell" it gave this client at logon - for one player every one of them in Frankfurt
 * (cache*-fra1/fra2), and all of them answered 503 for a freshly updated mod for hours
 * (2026-10-23). So the first list asks for more servers than the default, and when a chunk has
 * failed on everything in it, {@link #widen()} adds servers of other cells once and the download
 * goes on with them instead of giving up.
 *
 * <p>The list is a CopyOnWriteArrayList: download threads read it while one of them widens it.
 */
final class CdnServers {
    private static final String TAG = "Zomdroid/CdnServers";
    /** More than the default handful: Steam then mixes in neighbouring regions. */
    private static final int FIRST_LIST_SIZE = 40;
    /**
     * Cells to ask when the first list is exhausted. Any number is a valid cell to Steam, which
     * answers with servers near it; a spread of them reaches other regions without depending on
     * which number is which city.
     */
    private static final int[] OTHER_CELLS = {1, 4, 5, 14, 15, 25, 32, 33, 38, 50, 52, 63, 64, 66, 92, 116};
    private static final int ENOUGH_NEW = 12;

    final List<Server> list = new CopyOnWriteArrayList<>();
    private final SteamContent content;
    private boolean widened;

    private CdnServers(SteamContent content) {
        this.content = content;
    }

    /** The first list, or an empty one when Steam gives none. */
    static CdnServers fetch(SteamContent content) {
        CdnServers s = new CdnServers(content);
        List<Server> first = request(content, null, FIRST_LIST_SIZE);
        if (first == null || first.isEmpty()) first = request(content, null, null);
        if (first != null) s.list.addAll(first);
        return s;
    }

    /**
     * Adds servers of other cells, once per download. Returns true when new servers were added -
     * the caller then retries its chunk on them.
     */
    synchronized boolean widen() {
        if (widened) return false;
        widened = true;
        Set<String> known = new HashSet<>();
        for (Server s : list) known.add(host(s));
        int added = 0;
        for (int cell : OTHER_CELLS) {
            List<Server> more = request(content, cell, 10);
            if (more == null) continue;
            for (Server s : more) {
                if (known.add(host(s))) {
                    list.add(s);
                    added++;
                }
            }
            if (added >= ENOUGH_NEW) break;
        }
        Log.i(TAG, "widened the CDN list by " + added + " server(s) from other regions");
        return added > 0;
    }

    private static String host(Server s) {
        return s.getHost() != null ? s.getHost() : String.valueOf(s.getVHost());
    }

    private static List<Server> request(SteamContent content, Integer cell, Integer count) {
        try {
            return await(content.getServersForSteamPipe(cell, count, GlobalScope.INSTANCE), 30000);
        } catch (Exception e) {
            Log.w(TAG, "server list for cell " + cell + " failed: " + e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T await(Deferred<T> d, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!d.isCompleted()) {
            if (System.currentTimeMillis() > deadline) throw new TimeoutException("deferred timed out");
            Thread.sleep(40);
        }
        return (T) d.getCompleted();
    }
}
