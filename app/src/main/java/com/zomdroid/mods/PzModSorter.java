package com.zomdroid.mods;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Load-order sort from the rules mod authors put in mod.info: require (the required mod loads
 * first), loadModAfter and loadModBefore. Ported from RimDroid's ModSorter, without RimWorld's
 * Core and DLC.
 *
 * <p>The sort is stable: among mods free to go next it always takes the one that stood earliest, so
 * a mod moves only when a rule makes it, and a list that is already right comes back unchanged.
 * Contradicting rules (A before B, B before A) cannot all hold: they are reported, and the mod that
 * stood earliest goes first. Rules naming mods that are not active are ignored for the order.
 */
public final class PzModSorter {

    public static final class Result {
        public final List<String> order = new ArrayList<>();
        public int moved;
        /** Required but not installed: their ids. */
        public final List<String> missing = new ArrayList<>();
        /** Required, installed, but switched off. */
        public final List<PzModList.Mod> inactive = new ArrayList<>();
        /** Mods whose rules contradict each other. */
        public final List<String> conflicts = new ArrayList<>();
        /** "A ↔ B": both on, one says it is incompatible with the other. */
        public final List<String> incompatible = new ArrayList<>();
    }

    public static Result sort(List<String> current, Map<String, PzModList.Mod> installed) {
        Result r = new Result();
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(current));
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) pos.put(ids.get(i), i);

        Map<String, Set<String>> after = new HashMap<>();   // id -> ids that must come after it
        Map<String, Integer> incoming = new HashMap<>();
        for (String id : ids) { after.put(id, new LinkedHashSet<>()); incoming.put(id, 0); }
        for (String id : ids) {
            PzModList.Mod m = installed.get(id);
            if (m == null) continue;
            for (String b : m.loadBefore) edge(id, b, after, incoming);
            for (String a : m.loadAfter) edge(a, id, after, incoming);
            for (String d : m.require) edge(d, id, after, incoming);
        }

        // Kahn's algorithm, always taking the earliest-standing ready mod.
        PriorityQueue<String> ready = new PriorityQueue<>((a, b) -> pos.get(a) - pos.get(b));
        for (String id : ids) if (incoming.get(id) == 0) ready.add(id);
        Set<String> placed = new HashSet<>();
        while (placed.size() < ids.size()) {
            if (ready.isEmpty()) {
                // A cycle: report what is left, place the earliest of it to break it.
                String first = null;
                for (String id : ids) if (!placed.contains(id) && (first == null || pos.get(id) < pos.get(first))) first = id;
                if (r.conflicts.isEmpty()) for (String id : ids) if (!placed.contains(id) && incoming.get(id) > 0) r.conflicts.add(id);
                incoming.put(first, 0);
                ready.add(first);
            }
            String id = ready.poll();
            if (placed.contains(id)) continue;
            placed.add(id);
            r.order.add(id);
            for (String next : after.get(id)) {
                if (placed.contains(next)) continue;
                int n = incoming.get(next) - 1;
                incoming.put(next, n);
                if (n == 0) ready.add(next);
            }
        }
        for (int i = 0; i < r.order.size(); i++) if (!r.order.get(i).equals(ids.get(i))) r.moved++;

        // Warnings: what the active mods need, and incompatible pairs.
        Set<String> active = new HashSet<>(ids), seenMissing = new HashSet<>(), seenInactive = new HashSet<>();
        for (String id : ids) {
            PzModList.Mod m = installed.get(id);
            if (m == null) continue;
            for (String d : m.require) {
                if (active.contains(d)) continue;
                PzModList.Mod dep = installed.get(d);
                if (dep == null) { if (seenMissing.add(d)) r.missing.add(d); }
                else if (seenInactive.add(d)) r.inactive.add(dep);
            }
            for (String other : m.incompatible)
                if (active.contains(other)) {
                    PzModList.Mod o = installed.get(other);
                    r.incompatible.add(m.name + " ↔ " + (o != null ? o.name : other));
                }
        }
        return r;
    }

    /** "first loads before second", when both are in the list and the edge is new. */
    private static void edge(String first, String second, Map<String, Set<String>> after, Map<String, Integer> incoming) {
        if (first.equals(second) || !after.containsKey(first) || !after.containsKey(second)) return;
        if (after.get(first).add(second)) incoming.put(second, incoming.get(second) + 1);
    }

    private PzModSorter() {}
}
