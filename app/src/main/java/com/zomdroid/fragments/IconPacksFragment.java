package com.zomdroid.fragments;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zomdroid.R;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;
import com.zomdroid.input.IconPacks;

import java.util.ArrayList;
import java.util.List;

/**
 * Button icons, from the drawer (ported from RimDroid): the icon packs (built in and imported),
 * each with a few of its pictures and Apply / Remove buttons for the chosen instance's layout, and
 * the import of a pack zip. The pack format is described in {@link IconPacks}.
 */
public class IconPacksFragment extends Fragment {

    private static final int PREVIEW_ICONS = 6;

    private Spinner spInstance;
    /** The spinner starts on "Choose an instance" (several instances); real instances follow it. */
    private boolean choosePrompt;
    private LinearLayout packList;
    private final List<GameInstance> instances = new ArrayList<>();

    private final ActivityResultLauncher<String[]> pickPack =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onPackPicked);

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_icon_packs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spInstance = v.findViewById(R.id.sp_icon_packs_instance);
        packList = v.findViewById(R.id.ll_icon_packs);
        final TextView howto = v.findViewById(R.id.tv_icon_packs_howto);
        final TextView format = v.findViewById(R.id.tv_icon_packs_format);
        format.setText(getString(R.string.icon_packs_format, IconPacks.ACTION_NAMES));
        howto.setText(getString(R.string.icon_packs_howto) + " ▸");
        howto.setOnClickListener(b -> {
            boolean open = format.getVisibility() != View.VISIBLE;
            format.setVisibility(open ? View.VISIBLE : View.GONE);
            howto.setText(getString(R.string.icon_packs_howto) + (open ? " ▾" : " ▸"));
        });
        v.findViewById(R.id.btn_icon_packs_import).setOnClickListener(b ->
                pickPack.launch(new String[]{"application/zip", "application/x-zip-compressed",
                        "application/octet-stream"}));

        instances.clear();
        instances.addAll(GameInstanceManager.requireSingleton().getInstances());
        List<String> names = new ArrayList<>();
        if (instances.isEmpty()) names.add(getString(R.string.icon_packs_no_instances));
        // With several instances nothing is preselected: Apply would otherwise go to an instance the
        // player never chose (packs applied to 42.21 while playing 42.20, 2026-10-17).
        choosePrompt = instances.size() > 1;
        if (choosePrompt) names.add(getString(R.string.icon_packs_choose_instance));
        for (GameInstance gi : instances) names.add(gi.getName());
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(), R.layout.spinner_item, names);
        a.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spInstance.setAdapter(a);
        // Apply / Remove depend on the chosen instance's layout.
        spInstance.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View view, int pos, long id) { refreshPacks(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        refreshPacks();
    }

    @Nullable
    private GameInstance current() {
        int i = spInstance.getSelectedItemPosition() - (choosePrompt ? 1 : 0);
        return (i >= 0 && i < instances.size()) ? instances.get(i) : null;
    }

    /** One card per pack: name, count, a few pictures on dark tiles, Apply, Remove, Delete. */
    private void refreshPacks() {
        if (packList == null || !isAdded()) return;
        packList.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int tile = Math.round(36 * density), pad = Math.round(5 * density), gap = Math.round(4 * density);
        List<IconPacks.Pack> packs = IconPacks.list(requireContext());
        if (packs.isEmpty()) {
            TextView none = new TextView(requireContext());
            none.setText(R.string.icon_packs_none);
            packList.addView(none);
            return;
        }
        GameInstance gi = current();
        for (IconPacks.Pack pack : packs) {
            View row = getLayoutInflater().inflate(R.layout.item_icon_pack, packList, false);
            // Author, pack name, then (below) the layout it is made for.
            ((TextView) row.findViewById(R.id.tv_icon_pack_name)).setText(
                    pack.author != null ? pack.author + " – " + pack.name : pack.name);
            IconPacks.Status st = gi != null ? IconPacks.statusOnInstance(requireContext(), pack, gi) : null;
            StringBuilder info = new StringBuilder(getString(R.string.icon_packs_count, pack.icons.size()));
            Integer layout = layoutName(pack.layout);
            if (layout != null) info.append(" · ").append(getString(layout));
            if (pack.builtIn) info.append(" · ").append(getString(R.string.icon_packs_builtin));
            if (st != null && st.applied > 0) info.append(" · ").append(getString(R.string.icon_packs_applied_count, st.applied));
            ((TextView) row.findViewById(R.id.tv_icon_pack_info)).setText(info);
            LinearLayout preview = row.findViewById(R.id.ll_icon_pack_preview);
            for (int i = 0; i < pack.icons.size() && i < PREVIEW_ICONS; i++) {
                Bitmap bmp = IconPacks.load(requireContext(), pack, pack.icons.get(i));
                if (bmp == null) continue;
                ImageView iv = new ImageView(requireContext());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tile, tile);
                lp.setMarginEnd(gap);
                iv.setLayoutParams(lp);
                iv.setPadding(pad, pad, pad, pad);
                iv.setBackgroundColor(0xFF37474F);
                iv.setImageBitmap(bmp);
                preview.addView(iv);
            }
            // Apply is grey when it would change nothing; it lights up again when, say, a new button
            // the pack has a picture for is added to the layout.
            Button applyBtn = row.findViewById(R.id.btn_icon_pack_apply);
            applyBtn.setEnabled(st != null && st.pending > 0);
            applyBtn.setOnClickListener(b -> confirmApply(pack));
            Button removeBtn = row.findViewById(R.id.btn_icon_pack_remove);
            removeBtn.setEnabled(st != null && st.applied > 0);
            removeBtn.setOnClickListener(b -> confirmRemove(pack));
            Button delete = row.findViewById(R.id.btn_icon_pack_delete);
            delete.setVisibility(pack.builtIn ? View.GONE : View.VISIBLE);
            delete.setOnClickListener(b -> new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(getString(R.string.icon_packs_delete_confirm, pack.name))
                    .setPositiveButton(R.string.icon_packs_delete, (d, w) -> {
                        IconPacks.delete(pack);
                        refreshPacks();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
            packList.addView(row);
        }
    }

    @Nullable
    private static Integer layoutName(@Nullable String layout) {
        if (layout == null) return null;
        switch (layout) {
            case "full": return R.string.icon_packs_layout_full;
            case "vkbd": return R.string.icon_packs_layout_vkbd;
            case "gamepad": return R.string.icon_packs_layout_gamepad;
            default: return null;
        }
    }

    /** Applying rewrites the layout's icons: asked first, a stray tap must not do it. */
    private void confirmApply(IconPacks.Pack pack) {
        GameInstance gi = current();
        if (gi == null) { toast(getString(R.string.icon_packs_no_instances)); return; }
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.icon_packs_apply_confirm, pack.name, gi.getName()))
                .setPositiveButton(R.string.icon_packs_apply, (d, w) -> {
                    int n = IconPacks.applyToInstance(requireContext(), pack, gi);
                    if (n > 0) toast(getString(R.string.icon_packs_applied, n, gi.getName()));
                    else toast(getString(n == 0 ? R.string.icon_packs_applied_none : R.string.icon_packs_apply_failed));
                    refreshPacks();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Takes this pack's icons off the chosen instance's layout; asked first, like Apply. */
    private void confirmRemove(IconPacks.Pack pack) {
        GameInstance gi = current();
        if (gi == null) { toast(getString(R.string.icon_packs_no_instances)); return; }
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.icon_packs_remove_confirm, pack.name, gi.getName()))
                .setPositiveButton(R.string.icon_packs_unapply, (d, w) -> {
                    int n = IconPacks.removeFromInstance(requireContext(), pack, gi);
                    if (n >= 0) toast(getString(R.string.icon_packs_removed, n, gi.getName()));
                    else toast(getString(R.string.icon_packs_apply_failed));
                    refreshPacks();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onPackPicked(@Nullable Uri uri) {
        if (uri == null) return;
        String name = queryDisplayName(uri);
        final android.content.Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            String msg;
            try {
                java.util.List<IconPacks.Pack> packs = IconPacks.importZipAll(app, uri, name);
                if (packs.size() == 1) {
                    IconPacks.Pack p = packs.get(0);
                    msg = app.getString(R.string.icon_packs_imported, p.name, p.icons.size());
                } else {
                    msg = app.getString(R.string.icon_packs_imported_many, packs.size());
                }
            } catch (Exception e) {
                msg = app.getString(R.string.icon_packs_import_failed,
                        e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final String text = msg;
            android.app.Activity act = getActivity();
            if (act == null) return;
            act.runOnUiThread(() -> { toast(text); refreshPacks(); });
        }, "icon-pack-import").start();
    }

    @Nullable
    private String queryDisplayName(Uri uri) {
        try (Cursor c = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        String s = uri.getLastPathSegment();
        return s == null ? null : s.substring(s.lastIndexOf('/') + 1);
    }

    private void toast(String msg) {
        if (isAdded()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
