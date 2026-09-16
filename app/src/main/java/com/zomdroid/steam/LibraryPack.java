package com.zomdroid.steam;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Download sources are explicit: MP must never follow the current public B42 build. */
public enum LibraryPack {
    MACOS("macos", "macos", "public", 108602, 0L, "manifest.json", MacosLibraries.NAMES),
    B41_MULTIPLAYER("mp-b41", "linux", "unstable", 108603, 249541819024555413L,
            "zomdroid-mp-manifest.json", Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "libRakNet64.so", "libZNetNoSteam64.so"))));

    public final String id, os, branch, metadataName;
    public final int depot;
    public final long manifest;
    public final Set<String> names;

    LibraryPack(String id, String os, String branch, int depot, long manifest,
                String metadataName, Set<String> names) {
        this.id = id;
        this.os = os;
        this.branch = branch;
        this.depot = depot;
        this.manifest = manifest;
        this.metadataName = metadataName;
        this.names = names;
    }

    public String selectedName(String path) {
        if (this == MACOS) return MacosLibraries.selectedName(path);
        if (path == null) return null;
        String rel = path.replace('\\', '/');
        // B42.12 depot paths, not the desktop x86_64 files with identical basenames.
        if (rel.startsWith("projectzomboid/")) rel = rel.substring("projectzomboid/".length());
        String prefix = "android/arm64-v8a/";
        if (!rel.startsWith(prefix)) return null;
        String name = rel.substring(prefix.length());
        return names.contains(name) ? name : null;
    }
}
