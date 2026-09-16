package com.zomdroid;

import android.system.Os;
import android.util.AtomicFile;
import com.zomdroid.game.GameInstance;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Uses PZ's named hosting profiles, so choosing an existing name resumes that world. */
final class DedicatedServerProfile {
    final File root;
    final String name;
    int heapMb = 2048;
    String adminPassword = "";

    DedicatedServerProfile(GameInstance instance, String name) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,48}"))
            throw new IllegalArgumentException("Use 1–48 letters, digits, _ or - for the server name");
        this.name = name;
        root = new File(instance.getHomePath(), "coop-probe");
    }

    File config(String suffix) { return new File(root, "Server/" + name + suffix); }
    File preferences() { return new File(root, "dedicated-" + name + ".properties"); }

    void load() throws IOException {
        Properties p = new Properties();
        if (preferences().isFile()) try (InputStream in = new FileInputStream(preferences())) { p.load(in); }
        heapMb = Integer.parseInt(p.getProperty("heapMb", "2048"));
        adminPassword = p.getProperty("adminPassword", "");
    }

    void savePreferences() throws IOException {
        if (heapMb < 512 || heapMb > 16384) throw new IllegalArgumentException("Heap must be 512–16384 MB");
        if (adminPassword.trim().isEmpty() || adminPassword.indexOf('\n') >= 0 || adminPassword.indexOf('\r') >= 0)
            throw new IllegalArgumentException("An administrator password is required");
        Properties p = new Properties();
        p.setProperty("heapMb", Integer.toString(heapMb));
        p.setProperty("adminPassword", adminPassword);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        p.store(bytes, "Dedicated server settings; private, excluded from reports");
        write(preferences(), bytes.toByteArray());
    }

    String read(String suffix) throws IOException {
        File f = config(suffix);
        if (!f.isFile()) return "";
        if (f.length() > 1024 * 1024) throw new IOException("Configuration file is too large");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    void setOptions(Map<String, String> options) throws IOException {
        String text = read(".ini");
        Set<String> replaced = new HashSet<>();
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            int equals = line.indexOf('=');
            String key = equals < 0 ? "" : line.substring(0, equals).trim();
            if (options.containsKey(key)) {
                if (replaced.add(key)) result.append(key).append('=').append(options.get(key)).append('\n');
            } else if (!line.isEmpty()) result.append(line).append('\n');
        }
        for (Map.Entry<String, String> e : options.entrySet())
            if (replaced.add(e.getKey())) result.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        write(config(".ini"), result.toString().getBytes(StandardCharsets.UTF_8));
    }

    Properties options() throws IOException {
        Properties p = new Properties();
        // PZ ini is UTF-8 key=value; do not apply java.util.Properties backslash escaping.
        for (String line : read(".ini").split("\\r?\\n")) {
            int equals = line.indexOf('=');
            if (equals > 0 && !line.trim().startsWith("#"))
                p.setProperty(line.substring(0, equals).trim(), line.substring(equals + 1));
        }
        return p;
    }

    void prepareMods(GameInstance instance) throws Exception {
        File installed = new File(instance.getHomePath(), "Zomboid/mods");
        if (!installed.isDirectory() && !installed.mkdirs()) throw new IOException("Cannot create mod directory");
        File mods = new File(root, "mods");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create server directory");
        // Older probes made an empty directory. Preserve any actual hosting mods.
        if (mods.isDirectory() && !Files.isSymbolicLink(mods.toPath())) {
            String[] children = mods.list();
            if (children != null && children.length == 0 && !mods.delete()) throw new IOException("Cannot prepare mods");
        }
        if (!Files.exists(mods.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            Os.symlink(installed.getAbsolutePath(), mods.getAbsolutePath());
    }

    static void write(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = atomic.startWrite();
        try { out.write(bytes); atomic.finishWrite(out); }
        catch (IOException e) { atomic.failWrite(out); throw e; }
    }
}
