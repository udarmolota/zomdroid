package com.zomdroid;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.zip.CRC32;

public class FileUtils {

    static void extractTarXzToDisk(@NonNull InputStream inStream, @NonNull String destPath,
                                   TaskProgressListener taskProgressListener, long tarXzSize) throws IOException {
        XZCompressorInputStream xzCompressorInStream = new XZCompressorInputStream(inStream);
        extractTarToDisk(xzCompressorInStream, destPath, taskProgressListener, tarXzSize);
/*        TarArchiveInputStream tarArchiveInStream = new TarArchiveInputStream(xzCompressorInStream);
        TarArchiveEntry entry;
        while ((entry = tarArchiveInStream.getNextEntry()) != null) {
            extractArchiveEntry(tarArchiveInStream, entry, destPath);
        }*/
    }

    static void extractTarToDisk(@NonNull InputStream inStream, @NonNull String destPath,
                                 TaskProgressListener taskProgressListener, long tarSize) throws IOException {
        TarArchiveInputStream tarArchiveInStream = new TarArchiveInputStream(new BufferedInputStream(inStream, 1024 * 1024));
        TarArchiveEntry entry;
        while ((entry = tarArchiveInStream.getNextEntry()) != null) {
            extractArchiveEntry(tarArchiveInStream, entry, destPath);
            if (taskProgressListener != null) {
                int progress = -1;
                if (tarSize > 0)
                    progress = (int) ((tarArchiveInStream.getBytesRead() / (float) tarSize) * 100);
                taskProgressListener.onProgressUpdate(null, progress, 100);
            }
        }
    }

    static void extractZipToDisk(@NonNull InputStream inStream, @NonNull String destPath,
                                 TaskProgressListener taskProgressListener, long zipSize) throws IOException {
        ZipArchiveInputStream zipArchiveInStream = new ZipArchiveInputStream(new BufferedInputStream(inStream, 1024 * 1024));
        ZipArchiveEntry entry;
        while ((entry = zipArchiveInStream.getNextEntry()) != null) {
            extractArchiveEntry(zipArchiveInStream, entry, destPath);
            if (taskProgressListener != null) {
                int progress = -1;
                if (zipSize > 0)
                    progress = (int) ((zipArchiveInStream.getBytesRead() / (float) zipSize) * 100);
                taskProgressListener.onProgressUpdate(null, progress, 100);
            }
        }
    }

    static void extractArchiveEntry(ArchiveInputStream<?> archiveInStream, ArchiveEntry archiveEntry, String destPath) throws IOException {
        if (!archiveInStream.canReadEntryData(archiveEntry)) {
            throw new RuntimeException("Failed to read archive entry");
        }
        File file = new File(destPath + "/" + archiveEntry.getName());
        if (archiveEntry.isDirectory()) {
            if (!file.isDirectory() && !file.mkdirs()) {
                throw new IOException("Failed to create directory " + file);
            }
        } else {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Failed to create directory " + parent);
            }
            try (OutputStream fileOutStream = new BufferedOutputStream(new FileOutputStream(file), 1024 * 1024)) {
                IOUtils.copy(archiveInStream, fileOutStream);
            }
        }
    }

    public static long queryFileSize(ContentResolver contentResolver, Uri uri) {
        try (Cursor cursor = contentResolver.query(uri, null, null, null, null)) {
            if (cursor == null)
                throw new RuntimeException("Cursor from content resolver query is null");
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            if (sizeIndex == -1)
                throw new RuntimeException("Size column doesn't exist in cursor from content resolver query");
            cursor.moveToFirst();
            return cursor.getLong(sizeIndex);
        }
    }

    public static void copyAssetsToDisk(@NotNull Context context, @NotNull String assetPath, @NotNull String destinationPath) throws IOException {
        String[] assets = context.getAssets().list(assetPath);
        if (assets == null || assets.length == 0) {
            try (InputStream inputStream = context.getAssets().open(assetPath)) {
                File outFile = new File(destinationPath);
                try (FileOutputStream outStream = new FileOutputStream(outFile)) {
                    IOUtils.copy(inputStream, outStream);
                }
            }
            return;
        }

        File destinationDir = new File(destinationPath);
        if (!destinationDir.exists() && !destinationDir.mkdirs())
            throw new IOException("Failed to create directory " + destinationDir.getAbsolutePath());

        for (String asset : assets) {
            copyAssetsToDisk(context, assetPath + "/" + asset, destinationPath + "/" + asset);
        }
    }

    public static boolean deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
        }
        return directory.delete();
    }

    public static long generateCRC32ForAsset(@NonNull Context context, @NonNull String assetPath) throws IOException {
        CRC32 crc32 = new CRC32();
        try (InputStream inputStream = new BufferedInputStream(context.getAssets().open(assetPath))) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                crc32.update(buffer, 0, bytesRead);
            }
        }
        return crc32.getValue();
    }

    public static boolean isValidFilenameStrict(String filename) {
        if (filename == null || filename.trim().isEmpty()) return false;

        // disallow / \ ? % * : | " < >
        String pattern = "^[^\\\\/:*?\"<>|%]+$";

        if (!filename.matches(pattern)) return false;

        if (filename.startsWith(".")) return false;

        return filename.length() <= 40;
    }
}