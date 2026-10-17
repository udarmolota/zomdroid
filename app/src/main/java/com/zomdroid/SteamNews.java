package com.zomdroid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project Zomboid's official news for the launcher's home screen: The Indie Stone's own Steam announcements from
 * Steam's public news feed (no key, no login). The last answer is kept in a file, so the block is not
 * empty without a network; images are cached too. Everything here runs off the main thread.
 */
public final class SteamNews {
    private static final String TAG = "Zomdroid/News";
    private static final String FEED = "https://api.steampowered.com/ISteamNews/GetNewsForApp/v2/"
            + "?appid=108600&count=2&maxlength=0&feeds=steam_community_announcements&format=json";
    private static final String CLAN_IMAGES = "https://clan.akamai.steamstatic.com/images/";
    /** For a post with no picture of its own: Project Zomboid's store header. */
    private static final String FALLBACK_IMAGE =
            "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/108600/header.jpg";
    private static final Pattern CLAN_IMAGE = Pattern.compile("\\{STEAM_CLAN_IMAGE\\}/([^\\[\\]\"\\s]+)");
    private static final int SNIPPET_CHARS = 180;

    public static final class Item {
        public final String title, snippet, link, imageUrl;
        public final long date;   // seconds since 1970

        Item(String title, String snippet, String link, String imageUrl, long date) {
            this.title = title; this.snippet = snippet; this.link = link; this.imageUrl = imageUrl; this.date = date;
        }
    }

    private SteamNews() {}

    private static File cacheFile(Context c) { return new File(c.getFilesDir(), "steam_news.json"); }

    /** The news from the last successful fetch; empty if there was none. */
    public static List<Item> cached(Context c) {
        try {
            File f = cacheFile(c);
            if (!f.isFile()) return new ArrayList<>();
            return parse(new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Fresh news from Steam, saved for the next start; null if Steam could not be reached. */
    public static List<Item> fetch(Context c) {
        try {
            String json = new String(download(FEED), StandardCharsets.UTF_8);
            List<Item> items = parse(json);
            try (FileOutputStream out = new FileOutputStream(cacheFile(c))) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return items;
        } catch (Exception e) {
            Log.w(TAG, "news not fetched: " + e);
            return null;
        }
    }

    /** A post's picture, from the cache or downloaded into it, scaled down to about {@code width}. */
    public static Bitmap image(Context c, String url, int width) {
        try {
            File dir = new File(c.getCacheDir(), "news_images");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, Integer.toHexString(url.hashCode()));
            if (!f.isFile()) {
                byte[] data = download(url);
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            Log.w(TAG, "news image not loaded: " + e);
            return null;
        }
    }

    private static List<Item> parse(String json) throws Exception {
        List<Item> items = new ArrayList<>();
        JSONArray arr = new JSONObject(json).getJSONObject("appnews").getJSONArray("newsitems");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject n = arr.getJSONObject(i);
            String contents = clean(n.optString("contents"));
            Matcher m = CLAN_IMAGE.matcher(contents);
            String image = m.find() ? CLAN_IMAGES + m.group(1) : FALLBACK_IMAGE;
            String gid = n.optString("gid");
            // Steam's own page for the post, not the feed's redirect link.
            String link = gid.isEmpty() ? n.optString("url")
                    : "https://store.steampowered.com/news/app/108600/view/" + gid;
            items.add(new Item(clean(n.optString("title")), snippet(contents), link, image, n.optLong("date")));
        }
        return items;
    }

    /** The first lines as plain text: no BBCode, no pictures, no bare links. */
    private static String snippet(String contents) {
        String s = contents.replaceAll("(?is)\\[img[^\\]]*\\].*?\\[/img\\]", " ")
                .replaceAll("(?is)\\[h[1-6]\\].*?\\[/h[1-6]\\]", " ")
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("https?://\\S+", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.length() <= SNIPPET_CHARS) return s;
        int cut = s.lastIndexOf(' ', SNIPPET_CHARS);
        return s.substring(0, cut > SNIPPET_CHARS / 2 ? cut : SNIPPET_CHARS) + "…";
    }

    /** Drops broken characters (U+FFFD, lone surrogates), which Steam's feed can carry. */
    private static String clean(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '�') continue;
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    b.append(ch).append(s.charAt(++i));
                }
                continue;
            }
            if (Character.isLowSurrogate(ch)) continue;
            b.append(ch);
        }
        return b.toString();
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
