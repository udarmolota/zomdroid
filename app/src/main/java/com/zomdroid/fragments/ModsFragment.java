package com.zomdroid.fragments;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.zomdroid.GameActivity;
import com.zomdroid.R;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;
import com.zomdroid.mods.PzModList;
import com.zomdroid.mods.PzModSorter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Single-player mod lists, from the drawer (ported from RimDroid's mods screen; PzModList holds PZ's
 * files). The spinner picks one list across all instances: "New worlds" (mods/default.txt) or a
 * world's own mods.txt. Below, one list: "Active" in load order, then "Inactive" by name, with a
 * search field. Each row has a switch and, for mods installed in Zomboid/mods, a delete button; a
 * tap shows the mod's details. Active mods are dragged by their handle (written when the finger
 * lifts), and a long press offers up / down / top / bottom. "Sort" orders the active mods by their
 * authors' rules (PzModSorter) and shows what changes and what is missing before writing. While the
 * game runs the screen is read-only: the game rewrites these files itself.
 */
public class ModsFragment extends Fragment {

    private Spinner spTarget;
    private TextView tvRunning, tvUnreadable, tvEmpty;
    private EditText etSearch;
    private RecyclerView rv;
    private View btnSort, btnEnableAll, btnDisableAll;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();   // String = section header, Row = a mod
    private boolean editable;
    private boolean dragged;
    private ItemTouchHelper dragHelper;
    /** Mods of the instance the selected list belongs to, and the build rules it was read with. */
    private List<PzModList.Mod> mods = new ArrayList<>();
    private Map<String, PzModList.Mod> byId = new java.util.HashMap<>();

    /** One list the spinner offers: an instance's default.txt or one of its worlds. */
    private static final class Entry {
        final GameInstance instance;
        final PzModList.Target target;
        Entry(GameInstance instance, PzModList.Target target) { this.instance = instance; this.target = target; }
    }

    /** A row: the installed mod, or null for an active entry whose mod is not installed. */
    private static final class Row {
        final String id;
        @Nullable final PzModList.Mod mod;
        final boolean active;
        Row(String id, @Nullable PzModList.Mod mod, boolean active) { this.id = id; this.mod = mod; this.active = active; }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mods, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spTarget = v.findViewById(R.id.sp_mods_target);
        tvRunning = v.findViewById(R.id.tv_mods_game_running);
        tvUnreadable = v.findViewById(R.id.tv_mods_unreadable);
        tvEmpty = v.findViewById(R.id.tv_mods_empty);
        etSearch = v.findViewById(R.id.et_mods_search);
        rv = v.findViewById(R.id.rv_mods);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);
        dragHelper = new ItemTouchHelper(dragCallback);
        dragHelper.attachToRecyclerView(rv);

        loadEntries();
        spTarget.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int pos, long id) { refresh(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refresh(); }
        });
        v.findViewById(R.id.btn_mods_install).setOnClickListener(x ->
                Navigation.findNavController(v).navigate(R.id.action_open_install_mod));
        btnSort = v.findViewById(R.id.btn_mods_sort);
        btnSort.setOnClickListener(x -> sort());
        btnEnableAll = v.findViewById(R.id.btn_mods_enable_all);
        btnDisableAll = v.findViewById(R.id.btn_mods_disable_all);
        btnEnableAll.setOnClickListener(x -> enableAllShown());
        btnDisableAll.setOnClickListener(x -> disableAllShown());
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();   // mods may have been installed in the meantime
    }

    /**
     * Instances by name; each starts with its "(new)" list - the game menu's mods, for the next world
     * created - followed by its existing worlds. Labels lead with the instance and the mode so the
     * list reads alphabetically. With more than one list nothing is preselected: the spinner starts
     * on "Choose a mod list", so the player sees which list they edit instead of changing one they
     * did not notice was selected (her own slip, 2026-10-17).
     */
    private void loadEntries() {
        entries.clear();
        List<String> labels = new ArrayList<>();
        List<GameInstance> instances = new ArrayList<>(GameInstanceManager.requireSingleton().getInstances());
        java.util.Collections.sort(instances, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (GameInstance gi : instances) {
            for (PzModList.Target t : PzModList.targets(zomboidDir(gi))) {
                entries.add(new Entry(gi, t));
                if (t.newWorlds) {
                    labels.add(getString(R.string.mods_target_new_worlds, gi.getName()));
                    continue;
                }
                boolean dead = neverStarted(t.file.getParentFile());
                labels.add(getString(dead ? R.string.mods_target_world_never_started : R.string.mods_target_world,
                        gi.getName(), t.mode, t.world));
            }
        }
        if (entries.isEmpty()) {
            labels.add(getString(R.string.mods_no_instances));
        } else if (entries.size() > 1) {
            entries.add(0, null);   // the "Choose a mod list" row: no list
            labels.add(0, getString(R.string.mods_target_choose));
        }
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(), R.layout.spinner_item, labels);
        a.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spTarget.setAdapter(a);
        spTarget.setSelection(0);
    }

    /**
     * A world whose first start failed: the game shows it with a skull (getSaveInfo: no live player),
     * but no character was ever saved - players.db has no local player, or there is none, and no
     * Build 41 map_p.bin either. A character who died has a row with isDead=1 and is not counted:
     * that world can still be played with a new character.
     */
    private static boolean neverStarted(File saveDir) {
        if (saveDir == null || new File(saveDir, "map_p.bin").isFile()) return false;
        File db = new File(saveDir, "players.db");
        if (!db.isFile()) return true;
        try (android.database.sqlite.SQLiteDatabase sql = android.database.sqlite.SQLiteDatabase.openDatabase(
                db.getPath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
             android.database.Cursor c = sql.rawQuery("SELECT COUNT(*) FROM localPlayers", null)) {
            return c.moveToFirst() && c.getLong(0) == 0;
        } catch (Exception e) {
            return false;   // unreadable or another layout: do not accuse the world
        }
    }

    private static File zomboidDir(GameInstance gi) {
        return new File(gi.getHomePath(), "Zomboid");
    }

    @Nullable
    private Entry current() {
        int i = spTarget == null ? -1 : spTarget.getSelectedItemPosition();
        return (i >= 0 && i < entries.size()) ? entries.get(i) : null;
    }

    private void refresh() {
        if (rv == null) return;
        rows.clear();
        Entry e = current();
        boolean running = GameActivity.hasGameJvm();
        tvRunning.setVisibility(running ? View.VISIBLE : View.GONE);
        if (e == null) {
            tvUnreadable.setVisibility(View.GONE);
            tvEmpty.setText(entries.isEmpty() ? R.string.mods_no_instances : R.string.mods_choose_hint);
            tvEmpty.setVisibility(View.VISIBLE);
            btnSort.setEnabled(false);
            btnEnableAll.setEnabled(false);
            btnDisableAll.setEnabled(false);
            adapter.notifyDataSetChanged();
            return;
        }
        File zomboid = zomboidDir(e.instance);
        mods = PzModList.installed(zomboid, PzModList.gameVersion(zomboid), !"41".equals(e.instance.getBuildVersion()));
        byId = PzModList.byId(mods);
        List<String> order = PzModList.readOrder(e.target.file);
        tvUnreadable.setVisibility(order == null ? View.VISIBLE : View.GONE);
        editable = !running && order != null;
        btnSort.setEnabled(editable);
        btnEnableAll.setEnabled(editable);
        btnDisableAll.setEnabled(editable);

        String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        Set<String> activeIds = new HashSet<>();
        List<Row> active = new ArrayList<>(), inactive = new ArrayList<>();
        if (order != null) {
            for (String id : order) {
                if (!activeIds.add(id)) continue;
                Row r = new Row(id, byId.get(id), true);
                if (matches(r, q)) active.add(r);
            }
        }
        for (PzModList.Mod m : mods) {
            if (activeIds.contains(m.id) || byId.get(m.id) != m) continue;   // a second copy of one id
            Row r = new Row(m.id, m, false);
            if (matches(r, q)) inactive.add(r);
        }
        rows.add(getString(R.string.mods_active, active.size()));
        rows.addAll(active);
        rows.add(getString(R.string.mods_inactive, inactive.size()));
        rows.addAll(inactive);
        tvEmpty.setText(R.string.mods_empty);
        tvEmpty.setVisibility(mods.isEmpty() && active.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private static boolean matches(Row r, String q) {
        if (q.isEmpty()) return true;
        if (r.id.toLowerCase(Locale.ROOT).contains(q)) return true;
        return r.mod != null && (r.mod.name.toLowerCase(Locale.ROOT).contains(q)
                || r.mod.author.toLowerCase(Locale.ROOT).contains(q));
    }

    /** The active ids in load order, as the file has them now; null if unreadable. */
    @Nullable
    private List<String> order() {
        Entry e = current();
        return e == null ? null : PzModList.readOrder(e.target.file);
    }

    private void write(List<String> order) {
        Entry e = current();
        if (e == null) return;
        try {
            PzModList.writeOrder(e.target.file, order);
        } catch (IOException ex) {
            Toast.makeText(requireContext(), R.string.mods_switch_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** On: appended at the end of the load order, as the game does. Off: removed. */
    private void toggle(Row r, boolean on) {
        List<String> order = order();
        if (order == null) return;
        order.remove(r.id);
        if (on) order.add(r.id);
        write(order);
        refresh();
    }

    /** The rows on screen now (all mods, or what the search leaves) that are active or not. */
    private List<Row> shown(boolean active) {
        List<Row> out = new ArrayList<>();
        for (Object o : rows) if (o instanceof Row && ((Row) o).active == active) out.add((Row) o);
        return out;
    }

    /**
     * Every inactive mod shown goes to the end of the load order, as the game does with one - except
     * mods not made for this game build, which the game would drop again.
     */
    private void enableAllShown() {
        if (!editable) return;
        List<String> order = order();
        if (order == null) return;
        int added = 0;
        for (Row r : shown(false)) {
            if (r.mod == null || !r.mod.fitsBuild || order.contains(r.id)) continue;
            order.add(r.id);
            added++;
        }
        if (added == 0) return;
        write(order);
        Toast.makeText(requireContext(), getString(R.string.mods_enabled_n, added), Toast.LENGTH_LONG).show();
        refresh();
    }

    /** Every active mod shown is switched off; their load order is lost, so it asks first. */
    private void disableAllShown() {
        if (!editable) return;
        List<String> ids = new ArrayList<>();
        for (Row r : shown(true)) ids.add(r.id);
        if (ids.isEmpty()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.mods_disable_all_confirm, ids.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mods_disable_all, (d, w) -> {
                    List<String> order = order();
                    if (order == null) return;
                    order.removeAll(ids);
                    write(order);
                    refresh();
                })
                .show();
    }

    // ---- load order ----

    private void sort() {
        List<String> order = order();
        if (order == null) return;
        PzModSorter.Result r = PzModSorter.sort(order, byId);

        StringBuilder sb = new StringBuilder();
        sb.append(r.moved == 0 ? getString(R.string.mods_sort_nochange) : getString(R.string.mods_sort_moved, r.moved));
        if (!r.missing.isEmpty()) {
            sb.append("\n\n").append(getString(R.string.mods_sort_missing));
            for (String id : r.missing) sb.append("\n• ").append(id);
        }
        if (!r.inactive.isEmpty()) {
            sb.append("\n\n").append(getString(R.string.mods_sort_inactive));
            for (PzModList.Mod m : r.inactive) sb.append("\n• ").append(m.name);
        }
        if (!r.incompatible.isEmpty()) {
            sb.append("\n\n").append(getString(R.string.mods_sort_incompatible));
            for (String s : r.incompatible) sb.append("\n• ").append(s);
        }
        if (!r.conflicts.isEmpty()) {
            sb.append("\n\n").append(getString(R.string.mods_sort_conflicts));
            for (String id : r.conflicts) {
                PzModList.Mod m = byId.get(id);
                sb.append("\n• ").append(m != null ? m.name : id);
            }
        }
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.mods_sort_title)
                .setMessage(sb.toString());
        if (r.moved > 0) {
            b.setNegativeButton(android.R.string.cancel, null)
             .setPositiveButton(R.string.mods_sort_apply, (d, w) -> { write(r.order); refresh(); });
        } else {
            b.setPositiveButton(android.R.string.ok, null);
        }
        b.show();
    }

    private boolean draggable(int pos) {
        if (pos < 0 || pos >= rows.size() || !(rows.get(pos) instanceof Row)) return false;
        // Only the whole list: with a search filter the rows on screen are not the whole order.
        return editable && ((Row) rows.get(pos)).active && etSearch.getText().length() == 0;
    }

    private final ItemTouchHelper.Callback dragCallback = new ItemTouchHelper.Callback() {
        @Override public boolean isLongPressDragEnabled() { return false; }   // the handle starts it
        @Override public boolean isItemViewSwipeEnabled() { return false; }

        @Override
        public int getMovementFlags(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder h) {
            return draggable(h.getAdapterPosition()) ? makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) : 0;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
            int a = from.getAdapterPosition(), b = to.getAdapterPosition();
            if (!draggable(a) || !draggable(b)) return false;   // stays inside "Active"
            rows.add(b, rows.remove(a));
            adapter.notifyItemMoved(a, b);
            dragged = true;
            return true;
        }

        @Override public void onSwiped(@NonNull RecyclerView.ViewHolder h, int direction) {}

        @Override
        public void clearView(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder h) {
            super.clearView(r, h);
            if (!dragged) return;
            dragged = false;
            List<String> order = new ArrayList<>();
            for (Object o : rows) if (o instanceof Row && ((Row) o).active) order.add(((Row) o).id);
            write(order);
            rv.post(ModsFragment.this::refresh);   // after the drop animation
        }
    };

    /** Long press on an active mod: up / down / to the top / to the bottom of the load order. */
    private void showMoveMenu(View anchor, Row r) {
        if (!editable || !r.active) return;
        android.widget.PopupMenu pm = new android.widget.PopupMenu(requireContext(), anchor);
        pm.getMenu().add(0, 1, 0, R.string.mods_move_top);
        pm.getMenu().add(0, 2, 1, R.string.mods_move_up);
        pm.getMenu().add(0, 3, 2, R.string.mods_move_down);
        pm.getMenu().add(0, 4, 3, R.string.mods_move_bottom);
        pm.setOnMenuItemClickListener(item -> {
            List<String> order = order();
            if (order == null) return true;
            int i = order.indexOf(r.id);
            if (i < 0) return true;
            order.remove(i);
            int to;
            switch (item.getItemId()) {
                case 1: to = 0; break;
                case 2: to = Math.max(0, i - 1); break;
                case 3: to = Math.min(order.size(), i + 1); break;
                default: to = order.size(); break;
            }
            order.add(to, r.id);
            write(order);
            refresh();
            return true;
        });
        pm.show();
    }

    private void confirmDelete(PzModList.Mod m) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.mods_delete_title, m.name))
                .setMessage(R.string.mods_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mods_delete_confirm, (d, w) -> {
                    List<String> order = order();
                    if (order != null && order.remove(m.id)) write(order);
                    if (!PzModList.delete(m))
                        Toast.makeText(requireContext(), R.string.mods_switch_failed, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }

    private void showDetails(Row r) {
        StringBuilder sb = new StringBuilder();
        Bitmap poster = null;
        if (r.mod == null) {
            sb.append(getString(R.string.mods_not_installed));
        } else {
            PzModList.Mod m = r.mod;
            if (!m.author.isEmpty()) sb.append(getString(R.string.mods_author, m.author)).append('\n');
            sb.append(getString(R.string.mods_id, m.id)).append('\n');
            sb.append(getString(R.string.mods_folder, m.folder.getName())).append('\n');
            if (!m.versionMin.isEmpty() || !m.versionMax.isEmpty())
                sb.append(getString(R.string.mods_versions,
                        (m.versionMin.isEmpty() ? "…" : m.versionMin) + " – " + (m.versionMax.isEmpty() ? "…" : m.versionMax))).append('\n');
            if (!m.require.isEmpty()) sb.append(getString(R.string.mods_requires, String.join(", ", m.require))).append('\n');
            if (!m.fitsBuild) {
                Entry e = current();
                String version = e == null ? "" : PzModList.gameVersion(zomboidDir(e.instance));
                sb.append(getString(R.string.mods_wrong_build_detail,
                        version.isEmpty() ? getString(R.string.mods_this_game) : "Project Zomboid " + version)).append('\n');
            }
            if (!m.description.isEmpty()) {
                String d = m.description.replaceAll("\\[/?[a-zA-Z]+[^\\]]*\\]", "").replace("<br>", "\n").trim();
                if (d.length() > 600) d = d.substring(0, 600) + "…";
                sb.append('\n').append(d);
            }
            File f = m.posterFile();
            if (f != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                poster = BitmapFactory.decodeFile(f.getPath(), o);
            }
        }
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * dp);
        box.setPadding(pad, Math.round(8 * dp), pad, 0);
        if (poster != null) {
            ImageView iv = new ImageView(requireContext());
            iv.setImageBitmap(poster);
            iv.setAdjustViewBounds(true);
            iv.setMaxHeight(Math.round(180 * dp));
            box.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView tv = new TextView(requireContext());
        tv.setText(sb.toString().trim());
        tv.setTextIsSelectable(true);
        tv.setPadding(0, Math.round(8 * dp), 0, 0);
        box.addView(tv);
        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.addView(box);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(r.mod != null ? r.mod.name : r.id)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** The note under a mod's name: author, plus what is wrong with it in this list. */
    private String note(Row r, Set<String> activeIds) {
        if (r.mod == null) return getString(R.string.mods_not_installed);
        List<String> parts = new ArrayList<>();
        if (!r.mod.fitsBuild) parts.add(getString(R.string.mods_wrong_build));
        if (r.active) {
            for (String d : r.mod.require)
                if (!activeIds.contains(d)) { parts.add(getString(R.string.mods_needs, d)); break; }
            for (String o : r.mod.incompatible)
                if (activeIds.contains(o)) { parts.add(getString(R.string.mods_incompatible_with, o)); break; }
        } else {
            for (String id : activeIds) {
                PzModList.Mod m = byId.get(id);
                if (m != null && m.require.contains(r.id)) { parts.add(getString(R.string.mods_needed_by, m.name)); break; }
            }
        }
        if (!r.mod.author.isEmpty()) parts.add(r.mod.author);
        return String.join(" · ", parts);
    }

    private final RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mod, parent, false);
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            View v = h.itemView;
            Object item = rows.get(pos);
            TextView header = v.findViewById(R.id.tv_mod_header);
            View row = v.findViewById(R.id.row_mod);
            if (item instanceof String) {
                header.setVisibility(View.VISIBLE);
                header.setText((String) item);
                row.setVisibility(View.GONE);
                return;
            }
            header.setVisibility(View.GONE);
            row.setVisibility(View.VISIBLE);
            Row r = (Row) item;
            Set<String> activeIds = new HashSet<>();
            for (Object o : rows) if (o instanceof Row && ((Row) o).active) activeIds.add(((Row) o).id);
            TextView name = v.findViewById(R.id.tv_mod_name), sub = v.findViewById(R.id.tv_mod_sub);
            name.setText(r.mod != null ? r.mod.name : r.id);
            sub.setText(note(r, activeIds));
            sub.setVisibility(sub.getText().length() == 0 ? View.GONE : View.VISIBLE);
            // A mod the game will not load is marked in red: the game drops it from the list on save.
            if (sub.getTag() == null) sub.setTag(sub.getTextColors());
            boolean wrongBuild = r.mod != null && !r.mod.fitsBuild;
            if (wrongBuild) {
                sub.setTextColor(com.google.android.material.color.MaterialColors.getColor(sub,
                        androidx.appcompat.R.attr.colorError));
            } else {
                sub.setTextColor((android.content.res.ColorStateList) sub.getTag());
            }

            MaterialSwitch sw = v.findViewById(R.id.sw_mod);
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(r.active);
            sw.setEnabled(editable && (r.active || !wrongBuild));   // can be switched off, not on
            sw.setOnCheckedChangeListener((b, on) -> toggle(r, on));

            ImageButton del = v.findViewById(R.id.btn_mod_delete);
            boolean deletable = r.mod != null && r.mod.deletable;
            del.setVisibility(deletable ? View.VISIBLE : View.INVISIBLE);
            del.setEnabled(editable);
            del.setOnClickListener(x -> confirmDelete(r.mod));

            ImageView drag = v.findViewById(R.id.iv_mod_drag);
            boolean canDrag = draggable(pos);
            drag.setVisibility(r.active ? View.VISIBLE : View.GONE);
            drag.setAlpha(canDrag ? 1f : 0.3f);
            drag.setOnTouchListener((x, e) -> {
                if (canDrag && e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) dragHelper.startDrag(h);
                return canDrag;
            });
            row.setOnClickListener(x -> showDetails(r));
            row.setOnLongClickListener(x -> { showMoveMenu(x, r); return true; });
            row.setAlpha(r.mod == null ? 0.6f : 1f);
        }

        @Override public int getItemCount() { return rows.size(); }
    };
}
