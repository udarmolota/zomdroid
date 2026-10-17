package com.zomdroid.input;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zomdroid.AppStorage;
import com.zomdroid.C;
import com.zomdroid.game.GameInstance;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Icon packs for the on-screen buttons (ported from ValDroid/RimDroid): a set of pictures named
 * after Project Zomboid actions, put on a whole layout in one go (each button gets the icon of
 * what it does) and taken off again.
 *
 * <p>A pack is a zip of PNG files, white on a transparent background (the button tints them with
 * its colour), named by action - see {@link #ACTIONS}. Any other PNG is kept too, as an icon to
 * pick by hand. An optional pack.json gives the name: {"format": 1, "name": "..."}; "tint": false
 * marks colour pictures, shown in their own colours. Imported packs live in the app's home
 * (control_icon_packs/&lt;id&gt;/), built-in ones in the APK's assets. An icon put on a button is
 * copied into the instance's controls/icons folder as pack_&lt;id&gt;_&lt;icon&gt;.png, the folder the
 * buttons already read custom images from, so an exported layout carries it.
 */
public final class IconPacks {
    private IconPacks() {}

    public static final String ASSET_DIR = "control_icon_packs";
    private static final String PACKS_DIR = "control_icon_packs";
    private static final int MAX_ICON_PX = 256;

    public static final class Pack {
        public final String id;
        public final String name;
        public final boolean builtIn;
        /** False for colour pictures (pack.json "tint": false): shown untinted. */
        public final boolean tint;
        /** pack.json "author": who made the pictures, or null. */
        @Nullable public final String author;
        /** pack.json "layout": the layout the pack is made for - "full", "vkbd" or "gamepad" - or null. */
        @Nullable public final String layout;
        /** Icon names (file names without .png), sorted. */
        public final List<String> icons;

        Pack(String id, String name, boolean builtIn, boolean tint, List<String> icons,
             @Nullable String author, @Nullable String layout) {
            this.author = author;
            this.layout = layout;
            this.id = id;
            this.name = name;
            this.builtIn = builtIn;
            this.tint = tint;
            this.icons = icons;
        }
    }

    private static File importedDir() {
        return new File(AppStorage.requireSingleton().getHomePath(), PACKS_DIR);
    }

    /** The built-in packs, then the imported ones. */
    @NonNull
    public static List<Pack> list(@NonNull Context context) {
        List<Pack> packs = new ArrayList<>();
        try {
            String[] ids = context.getAssets().list(ASSET_DIR);
            if (ids != null) {
                Arrays.sort(ids);
                for (String id : ids) {
                    String[] files = context.getAssets().list(ASSET_DIR + "/" + id);
                    if (files == null || files.length == 0) continue;
                    byte[] json = null;
                    try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + id + "/pack.json")) {
                        json = readAll(in);
                    } catch (IOException ignored) {
                    }
                    packs.add(new Pack(id, packName(json, id), true, packTint(json), iconNames(files),
                            packField(json, "author"), packField(json, "layout")));
                }
            }
        } catch (IOException ignored) {
        }
        File[] dirs = importedDir().listFiles(File::isDirectory);
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File d : dirs) {
                if (d.getName().endsWith(".importing")) continue;
                byte[] json = null;
                File jsonFile = new File(d, "pack.json");
                if (jsonFile.isFile()) {
                    try (InputStream in = new FileInputStream(jsonFile)) {
                        json = readAll(in);
                    } catch (IOException ignored) {
                    }
                }
                String[] files = d.list();
                packs.add(new Pack(d.getName(), packName(json, d.getName()), false, packTint(json),
                        iconNames(files != null ? files : new String[0]),
                        packField(json, "author"), packField(json, "layout")));
            }
        }
        return packs;
    }

    private static List<String> iconNames(String[] files) {
        List<String> icons = new ArrayList<>();
        for (String f : files)
            if (f.toLowerCase(Locale.ROOT).endsWith(".png")) icons.add(f.substring(0, f.length() - 4));
        Collections.sort(icons);
        return icons;
    }

    private static boolean packTint(byte[] json) {
        if (json == null) return true;
        try {
            return new JSONObject(new String(json, StandardCharsets.UTF_8)).optBoolean("tint", true);
        } catch (Exception e) {
            return true;
        }
    }

    @Nullable
    private static String packField(byte[] json, String key) {
        if (json == null) return null;
        try {
            String v = new JSONObject(new String(json, StandardCharsets.UTF_8)).optString(key, "").trim();
            return v.isEmpty() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private static String packName(byte[] json, String fallback) {
        if (json == null) return fallback;
        try {
            String n = new JSONObject(new String(json, StandardCharsets.UTF_8)).optString("name", "").trim();
            return n.isEmpty() ? fallback : n;
        } catch (Exception e) {
            return fallback;
        }
    }

    @Nullable
    public static Bitmap load(@NonNull Context context, @NonNull Pack pack, @NonNull String icon) {
        if (pack.builtIn) {
            try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + pack.id + "/" + icon + ".png")) {
                return BitmapFactory.decodeStream(in);
            } catch (IOException e) {
                return null;
            }
        }
        File f = new File(new File(importedDir(), pack.id), icon + ".png");
        return f.isFile() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }

    /**
     * Imports a pack zip: every PNG (scaled down to 256 px, transparent borders trimmed) and
     * pack.json. Its id comes from the zip's file name; importing the same name again replaces it.
     * Blocking - run it off the UI thread. Returns the pack.
     */
    @NonNull
    public static Pack importZip(@NonNull Context context, @NonNull Uri uri, @Nullable String displayName)
            throws IOException {
        String base = displayName != null ? displayName.replaceAll("(?i)\\.zip$", "") : "pack";
        String id = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (id.isEmpty()) id = "pack";
        File dir = new File(importedDir(), id);
        File tmp = new File(importedDir(), id + ".importing");
        deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
        int count = 0;
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("cannot open the file");
            ZipInputStream zip = new ZipInputStream(raw);
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                // Only the file name: an entry path must never leave the pack folder (zip slip).
                String name = new File(e.getName()).getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("pack.json")) {
                    writeFile(new File(tmp, "pack.json"), readAll(zip));
                } else if (lower.endsWith(".png")) {
                    String icon = lower.substring(0, lower.length() - 4).replaceAll("[^a-z0-9_-]+", "_");
                    Bitmap bmp = decodeScaled(readAll(zip));
                    if (bmp == null || icon.isEmpty()) continue;
                    try (FileOutputStream out = new FileOutputStream(new File(tmp, icon + ".png"))) {
                        trimTransparentBorder(bmp).compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                    count++;
                }
            }
        } catch (IOException e) {
            deleteTree(tmp);
            throw e;
        }
        if (count == 0) {
            deleteTree(tmp);
            throw new IOException("no PNG pictures in the zip");
        }
        deleteTree(dir);
        if (!tmp.renameTo(dir)) {
            deleteTree(tmp);
            throw new IOException("cannot store the pack");
        }
        for (Pack p : list(context))
            if (!p.builtIn && p.id.equals(id)) return p;
        throw new IOException("the pack did not store");
    }

    public static boolean delete(@NonNull Pack pack) {
        return !pack.builtIn && deleteTree(new File(importedDir(), pack.id));
    }

    // ---- which icon a button gets ----------------------------------------------------------

    // Project Zomboid's own default keys, keyboard / mouse and gamepad (B42.21's
    // CharacterJoypadButtonBinding defaults). A button bound to exactly this one key gets the first
    // of these icons the pack has; buttons with several bindings keep what they show.
    private static final Map<String, String[]> ACTIONS = new HashMap<>();

    private static void action(String binding, String... icons) {
        ACTIONS.put(binding, icons);
    }

    static {
        action("MOUSE_BUTTON_LEFT", "attack");
        action("MOUSE_BUTTON_RIGHT", "aim");
        action("KEY_SPACE", "shove");
        action("KEY_LEFT_SHIFT", "run");
        action("KEY_LEFT_ALT", "sprint");
        action("KEY_C", "crouch");
        action("KEY_E", "interact");
        action("KEY_Q", "shout");
        action("KEY_R", "reload");
        action("KEY_V", "vehicle");
        action("KEY_H", "health");
        action("KEY_I", "inventory");
        action("KEY_M", "map");
        action("KEY_T", "chat");
        action("KEY_ESCAPE", "menu");
        action("MOUSE_WHEEL_UP", "zoom_in");
        action("MOUSE_WHEEL_DOWN", "zoom_out");
        action("KEY_W", "move_up");
        action("KEY_S", "move_down");
        action("KEY_A", "move_left");
        action("KEY_D", "move_right");
        action("KEY_B", "build");
        action("UI_TOGGLE_KEYBOARD", "keyboard");
        action("UI_TOGGLE_OVERLAY", "toggle_controls");
        // The gamepad layout: a face button takes its glyph from a gamepad pack (A bottom, B right,
        // X left, Y top), otherwise the icon of what PZ does with it.
        action("GAMEPAD_BUTTON_A", "pad_a", "interact");
        action("GAMEPAD_BUTTON_B", "pad_b", "close");
        action("GAMEPAD_BUTTON_X", "pad_x", "loot");
        action("GAMEPAD_BUTTON_Y", "pad_y", "inventory");
        action("GAMEPAD_RTRIGGER", "attack");
        action("GAMEPAD_LTRIGGER", "shove");
        action("GAMEPAD_BUTTON_RB", "reload");
        action("GAMEPAD_BUTTON_LSTICK", "crouch");
        action("GAMEPAD_BUTTON_RSTICK", "sprint", "run");
        action("GAMEPAD_BUTTON_START", "menu");
    }

    /** Action names a pack can use, for the "how to make a pack" text. */
    public static final String ACTION_NAMES = "attack, aim, shove, run, sprint, crouch, interact, "
            + "shout, reload, vehicle, health, inventory, map, chat, menu, build, zoom_in, zoom_out, "
            + "move_up, move_down, move_left, move_right, keyboard, toggle_controls, close, loot, "
            + "pad_a, pad_b, pad_x, pad_y";

    private static boolean isButton(@Nullable String type) {
        if (type == null) return false;
        switch (type) {
            case "BUTTON_CIRCLE": case "BUTTON_RECT":
            case "DPAD_UP": case "DPAD_RIGHT": case "DPAD_DOWN": case "DPAD_LEFT":
                return true;
            default:
                return false;
        }
    }

    /** The pack icon a described button should show, or null when the pack has none for its action. */
    @Nullable
    private static String packIconFor(@NonNull Pack pack, @NonNull JsonObject d) {
        if (!isButton(optString(d, "type"))) return null;
        JsonElement b = d.get("bindings");
        if (b == null || !b.isJsonArray() || b.getAsJsonArray().size() != 1) return null;
        String[] icons = ACTIONS.get(b.getAsJsonArray().get(0).getAsString());
        if (icons == null) return null;
        for (String icon : icons) if (pack.icons.contains(icon)) return icon;
        return null;
    }

    private static String prefix(Pack pack) {
        return "pack_" + pack.id + "_";
    }

    private static String iconFileName(Pack pack, String icon) {
        return prefix(pack) + icon + ".png";
    }

    // ---- an instance's saved layout --------------------------------------------------------

    private static File controlsDir(GameInstance gi) {
        return new File(gi.getGamePath(), "controls");
    }

    /** An instance's saved layout, or the bundled default when it has none; null when unreadable. */
    @Nullable
    private static JsonArray readLayout(@NonNull Context context, @NonNull GameInstance gi) {
        String json = null;
        File f = new File(controlsDir(gi), "controls.json");
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                json = new String(readAll(in), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }
        if (json == null) {
            try (InputStream in = context.getAssets().open(C.assets.DEFAULT_CONTROLS)) {
                json = new String(readAll(in), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            return root.isJsonArray() ? root.getAsJsonArray() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean writeLayout(@NonNull GameInstance gi, @NonNull JsonArray layout) {
        File dir = controlsDir(gi);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        try {
            writeFile(new File(dir, "controls.json"), layout.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Where a pack stands on an instance's layout. */
    public static final class Status {
        /** Buttons showing an icon of this pack. */
        public final int applied;
        /** Buttons Apply would change: the pack has their action's icon and they show something else. */
        public final int pending;

        Status(int applied, int pending) {
            this.applied = applied;
            this.pending = pending;
        }
    }

    @NonNull
    public static Status statusOnInstance(@NonNull Context context, @NonNull Pack pack, @NonNull GameInstance gi) {
        JsonArray layout = readLayout(context, gi);
        if (layout == null) return new Status(0, 0);
        int applied = 0, pending = 0;
        for (JsonElement e : layout) {
            if (!e.isJsonObject()) continue;
            JsonObject d = e.getAsJsonObject();
            String current = optString(d, "iconFile");
            if (current != null && current.startsWith(prefix(pack))) applied++;
            String icon = packIconFor(pack, d);
            if (icon != null && (!iconFileName(pack, icon).equals(current) || optBool(d, "noTint") == pack.tint))
                pending++;
        }
        return new Status(applied, pending);
    }

    /**
     * Puts the pack on every button of an instance's layout whose action it has an icon for; the
     * others keep what they show. An instance still on the bundled default gets that default first.
     * Returns how many buttons got an icon, or -1 when the layout could not be read or saved.
     */
    public static int applyToInstance(@NonNull Context context, @NonNull Pack pack, @NonNull GameInstance gi) {
        JsonArray layout = readLayout(context, gi);
        File icons = new File(controlsDir(gi), "icons");
        if (layout == null || (!icons.isDirectory() && !icons.mkdirs())) return -1;
        int applied = 0;
        for (JsonElement e : layout) {
            if (!e.isJsonObject()) continue;
            JsonObject d = e.getAsJsonObject();
            String icon = packIconFor(pack, d);
            if (icon == null) continue;
            Bitmap bmp = load(context, pack, icon);
            if (bmp == null) continue;
            String fileName = iconFileName(pack, icon);
            try (FileOutputStream out = new FileOutputStream(new File(icons, fileName))) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (IOException ex) {
                continue;
            }
            d.addProperty("iconFile", fileName);
            d.addProperty("noTint", !pack.tint);
            applied++;
        }
        if (applied > 0 && !writeLayout(gi, layout)) return -1;
        return applied;
    }

    /**
     * Takes this pack's icons off an instance's layout; icons from other packs or picked by hand
     * stay. Those buttons show their label again. Returns how many buttons lost an icon, or -1
     * when the layout could not be read or saved.
     */
    public static int removeFromInstance(@NonNull Context context, @NonNull Pack pack, @NonNull GameInstance gi) {
        JsonArray layout = readLayout(context, gi);
        if (layout == null) return -1;
        int removed = 0;
        for (JsonElement e : layout) {
            if (!e.isJsonObject()) continue;
            JsonObject d = e.getAsJsonObject();
            String current = optString(d, "iconFile");
            if (current == null || !current.startsWith(prefix(pack))) continue;
            d.remove("iconFile");
            d.addProperty("noTint", false);
            removed++;
        }
        if (removed > 0 && !writeLayout(gi, layout)) return -1;
        // The copies are no longer referenced by this layout.
        File[] copies = new File(controlsDir(gi), "icons").listFiles(
                (dir, name) -> name.startsWith(prefix(pack)));
        if (copies != null) for (File c : copies) c.delete();
        return removed;
    }

    @Nullable
    private static String optString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? null : e.getAsString();
    }

    private static boolean optBool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    // ---- pictures -------------------------------------------------------------------------

    @Nullable
    private static Bitmap decodeScaled(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > MAX_ICON_PX || bounds.outHeight / sample > MAX_ICON_PX) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    /**
     * Crops fully/near-transparent borders off an icon so its visible content fills the button's
     * icon box. Returns the original bitmap if there is nothing to trim or it is entirely transparent.
     */
    private static Bitmap trimTransparentBorder(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        final int ALPHA_MIN = 8; // treat alpha <= 8 as transparent
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (((px[row + x] >>> 24) & 0xff) > ALPHA_MIN) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) return src;
        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) return src;
        return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    // ---- files ----------------------------------------------------------------------------

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static boolean deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        return !f.exists() || f.delete();
    }
}
