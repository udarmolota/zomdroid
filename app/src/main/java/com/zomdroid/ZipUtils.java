package com.zomdroid;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipUtils {
    private ZipUtils() {}

    public static void zipDirectoryToStream(File rootDir, OutputStream out) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(out, 1024 * 1024));

        String basePath = rootDir.getCanonicalPath();
        try {
            zipWalk(rootDir, basePath, zos);
            zos.finish();
            zos.flush();
        } finally {
            // НЕ закрываем out здесь; out закроет вызывающий код (InstallerService)
            try { zos.close(); } catch (Exception ignored) {}
        }
    }

    private static void zipWalk(File file, String basePath, ZipOutputStream zos) throws IOException {
        File[] kids = file.listFiles();
        if (kids == null) return;

        for (File f : kids) {
            if (f.isDirectory()) {
                // directory entry (nice-to-have)
                String dirFull = f.getCanonicalPath();
                String dirRel = dirFull.substring(basePath.length());
                if (dirRel.startsWith(File.separator)) dirRel = dirRel.substring(1);
                dirRel = dirRel.replace('\\', '/') + "/";

                ZipEntry dirEntry = new ZipEntry(dirRel);
                dirEntry.setTime(f.lastModified());
                zos.putNextEntry(dirEntry);
                zos.closeEntry();

                zipWalk(f, basePath, zos);
                continue;
            }

            String full = f.getCanonicalPath();
            String rel = full.substring(basePath.length());
            if (rel.startsWith(File.separator)) rel = rel.substring(1);
            rel = rel.replace('\\', '/');

            ZipEntry entry = new ZipEntry(rel);
            entry.setTime(f.lastModified());
            zos.putNextEntry(entry);

            try (InputStream is = new BufferedInputStream(new FileInputStream(f), 1024 * 1024)) {
                byte[] buf = new byte[1024 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) {
                    zos.write(buf, 0, n);
                }
            }
            zos.closeEntry();
        }
    }
}
