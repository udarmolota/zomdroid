package com.zomdroid.fragments;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.zomdroid.R;
import com.zomdroid.databinding.FragmentProjectsBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The author's other projects. The list is a JSON file in a public GitHub repository, so a new
 * project or a changed link reaches everyone without a launcher release; the last copy that came
 * through is kept on disk, and the one bundled in assets covers a first start without a network.
 * Each project's latest version is read from its GitHub releases.
 *
 * <p>projects.json: {"reddit": url, "projects": [{"name", "description" (a string, or an object by
 * language code with "en" as the fallback), "icon" (a drawable name or an https URL), "github"
 * ("owner/repo"), "url" (optional page, defaults to the GitHub repo)}]}.
 */
public class ProjectsFragment extends Fragment {

    private static final String LOG_TAG = "ProjectsFragment";
    static final String REMOTE_URL = "https://raw.githubusercontent.com/udarmolota/projects/main/projects.json";
    private static final String ASSET = "projects/projects.json";
    private static final String CACHE = "projects.json";

    private FragmentProjectsBinding binding;
    private String redditUrl = "https://www.reddit.com/r/UdarMolotaDev";

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentProjectsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.projectsRedditCard.setOnClickListener(v -> open(redditUrl));

        Context app = requireContext().getApplicationContext();
        String local = readCache(app);
        if (local == null) local = readAsset(app);
        if (local != null) show(local);

        // Refresh from GitHub; on success the fresh list replaces what is on screen.
        new Thread(() -> {
            try {
                String fresh = new String(get(REMOTE_URL), StandardCharsets.UTF_8);
                new JSONObject(fresh);   // only a list that parses replaces the cached one
                writeCache(app, fresh);
                onUi(() -> show(fresh));
            } catch (Exception e) {
                Log.i(LOG_TAG, "project list not refreshed: " + e);
            }
        }, "projects-list").start();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void show(String json) {
        if (binding == null) return;
        try {
            JSONObject root = new JSONObject(json);
            String reddit = root.optString("reddit", "");
            if (reddit.startsWith("https://")) redditUrl = reddit;
            JSONArray list = root.optJSONArray("projects");
            binding.projectsList.removeAllViews();
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                if (p != null) addCard(p);
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "bad project list: " + e);
        }
    }

    private void addCard(JSONObject p) {
        View card = getLayoutInflater().inflate(R.layout.item_project, binding.projectsList, false);
        String name = p.optString("name", "");
        String github = p.optString("github", "");
        String page = p.optString("url", github.isEmpty() ? "" : "https://github.com/" + github);

        ((TextView) card.findViewById(R.id.project_name_tv)).setText(name);
        ((TextView) card.findViewById(R.id.project_description_tv)).setText(description(p));

        Button download = card.findViewById(R.id.project_download_btn);
        Button pageBtn = card.findViewById(R.id.project_page_btn);
        if (github.isEmpty()) {
            download.setVisibility(View.GONE);
        } else {
            download.setOnClickListener(v -> open("https://github.com/" + github + "/releases/latest"));
        }
        if (page.startsWith("https://")) pageBtn.setOnClickListener(v -> open(page));
        else pageBtn.setVisibility(View.GONE);

        ImageView icon = card.findViewById(R.id.project_icon_iv);
        String iconRef = p.optString("icon", "");
        if (iconRef.startsWith("https://")) {
            // The picture built in under the link's file name (icons/project_valdroid.png ->
            // project_valdroid) covers a first open without a network; the saved or downloaded
            // copy replaces it.
            String file = iconRef.substring(iconRef.lastIndexOf('/') + 1);
            int dot = file.lastIndexOf('.');
            setBuiltInIcon(icon, dot > 0 ? file.substring(0, dot) : file);
            loadIcon(icon, iconRef);
        } else if (!iconRef.isEmpty()) {
            setBuiltInIcon(icon, iconRef);
        }

        if (!github.isEmpty()) loadVersion(card.findViewById(R.id.project_version_tv), github);
        binding.projectsList.addView(card);
    }

    private void setBuiltInIcon(ImageView icon, String name) {
        String pkg = requireContext().getPackageName();
        int res = getResources().getIdentifier(name, "drawable", pkg);
        if (res == 0) res = getResources().getIdentifier(name, "mipmap", pkg);   // our own app icon
        if (res != 0) icon.setImageResource(res);
    }

    /** The description in the launcher's language, English otherwise. */
    private String description(JSONObject p) {
        JSONObject byLang = p.optJSONObject("description");
        if (byLang == null) return p.optString("description", "");
        String lang = getResources().getConfiguration().getLocales().get(0).getLanguage();
        String text = byLang.optString(lang, "");
        if (text.isEmpty() && "in".equals(lang)) text = byLang.optString("id", "");
        return text.isEmpty() ? byLang.optString("en", "") : text;
    }

    private void loadVersion(TextView view, String github) {
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(new String(
                        get("https://api.github.com/repos/" + github + "/releases/latest"), StandardCharsets.UTF_8));
                String tag = rel.optString("tag_name", "");
                if (tag.isEmpty()) return;
                onUi(() -> {
                    view.setText(getString(R.string.projects_latest_version, tag));
                    view.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                Log.i(LOG_TAG, "no release info for " + github + ": " + e);
            }
        }, "projects-version").start();
    }

    /**
     * An icon by URL: the copy saved last time first, so it shows without a network, then the
     * current one from the network, which replaces the saved copy when it decodes.
     */
    private void loadIcon(ImageView view, String url) {
        File cached = new File(new File(requireContext().getFilesDir(), ICON_CACHE_DIR), iconCacheName(url));
        new Thread(() -> {
            if (cached.isFile()) {
                Bitmap old = BitmapFactory.decodeFile(cached.getAbsolutePath());
                if (old != null) onUi(() -> view.setImageBitmap(old));
            }
            try {
                byte[] data = get(url);
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bmp == null) return;
                onUi(() -> view.setImageBitmap(bmp));
                File dir = cached.getParentFile();
                if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                    File tmp = new File(dir, cached.getName() + ".tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        out.write(data);
                    }
                    if (!tmp.renameTo(cached)) Log.w(LOG_TAG, "icon not cached: " + url);
                }
            } catch (Exception e) {
                Log.i(LOG_TAG, "icon not loaded: " + e);
            }
        }, "projects-icon").start();
    }

    private static final String ICON_CACHE_DIR = "project_icons";

    /** A file name from the URL (its SHA-1), so a changed link never shows an old picture. */
    private static String iconCacheName(String url) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(java.util.Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Log.w(LOG_TAG, "cannot open " + url + ": " + e);
        }
    }

    private void onUi(Runnable r) {
        android.app.Activity act = getActivity();
        if (act != null) act.runOnUiThread(() -> {
            if (binding != null && isAdded()) r.run();
        });
    }

    // -------------------- storage and network --------------------

    private static byte[] get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Accept", url.contains("api.github.com") ? "application/vnd.github+json" : "*/*");
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 4 * 1024 * 1024) throw new java.io.IOException("too large");
                }
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    @Nullable
    private static String readAsset(Context ctx) {
        try (InputStream in = ctx.getAssets().open(ASSET)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String readCache(Context ctx) {
        File f = new File(ctx.getFilesDir(), CACHE);
        if (!f.isFile()) return null;
        try (InputStream in = new java.io.FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Context ctx, String json) {
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), CACHE))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(LOG_TAG, "project list not cached: " + e);
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
