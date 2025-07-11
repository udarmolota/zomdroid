package com.zomdroid;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/*
 * Originally from Termux app with a few modifications
 * */
public class AppStorageProvider extends DocumentsProvider {
    private static final String TAG = "AppStorageProvider";
    private static final String ALL_MIME_TYPES = "*/*";

    private File baseDir;


    // The default columns to return information about a root if no specific
    // columns are requested in a query.
    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
    };

    // The default columns to return information about a document if no specific
    // columns are requested in a query.
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
    };

    private void setNotificationUri(Cursor cursor) {
        var context = getContext();
        if (context != null) {
            var baseUri = DocumentsContract.buildChildDocumentsUri(C.STORAGE_PROVIDER_AUTHORITY, baseDir.getAbsolutePath());
            cursor.setNotificationUri(context.getContentResolver(), baseUri);
        }
    }

    @Override
    public boolean onCreate() {
        AppStorage.init(requireContext());
        baseDir = new File(AppStorage.requireSingleton().getHomePath());
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        var result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        var applicationName = requireContext().getString(R.string.app_name);


        var row = result.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, getDocIdForFile(baseDir));
        row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher);
        row.add(DocumentsContract.Root.COLUMN_TITLE, applicationName);
        row.add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE | DocumentsContract.Root.FLAG_SUPPORTS_SEARCH | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD | DocumentsContract.Root.FLAG_SUPPORTS_RECENTS);
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, getDocIdForFile(baseDir));
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, null);
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES);
        row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, baseDir.getFreeSpace());
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        var result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        setNotificationUri(result);
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryRecentDocuments(String rootId, String[] projection) throws FileNotFoundException {
        var dir = getFileForDocId(rootId);
        try (var walk = Files.walk(dir.toPath())) {
            var iterator = walk
                    .filter(f -> f.toFile().isFile())
                    .sorted(Comparator.comparingLong((Path a) -> a.toFile().lastModified()).reversed())
                    .limit(64)
                    .iterator();

            var result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
            setNotificationUri(result);
            while (iterator.hasNext()) {
                includeFile(result, null, iterator.next().toFile());
            }
            return result;
        } catch (IOException e) {
            throw handleIOExceptionFromFilesWalk(e, dir);
        }
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        var result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        setNotificationUri(result);
        var files = getFileForDocId(parentDocumentId).listFiles();
        if (files != null) {
            for (var file : files) {
                includeFile(result, null, file);
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(final String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        final int accessMode = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, accessMode);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        return new AssetFileDescriptor(pfd, 0, file.length());
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File newFile = new File(parentDocumentId, displayName);
        int noConflictId = 2;
        while (newFile.exists()) {
            newFile = new File(parentDocumentId, displayName + " (" + noConflictId++ + ")");
        }
        try {
            boolean succeeded;
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                succeeded = newFile.mkdir();
            } else {
                succeeded = newFile.createNewFile();
            }
            if (!succeeded) {
                throw new FileNotFoundException("Failed to create document with id " + newFile.getPath());
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create document with id " + newFile.getPath());
        }
        notifyFileChange();
        return newFile.getPath();
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        var oldFile = getFileForDocId(documentId);
        var newFile = new File(oldFile.getParent(), displayName);
        if (newFile.exists()) {
            throw new FileNotFoundException("File already exists: " + displayName);
        }
        var pathsToInvalidate = findAllPathsIn(oldFile);
        if (!oldFile.renameTo(newFile)) {
            throw new FileNotFoundException("Unable to rename " + documentId);
        }
        revokeDocumentsPermission(pathsToInvalidate);
        return getDocIdForFile(newFile);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        var file = getFileForDocId(documentId);
        try (var walk = Files.walk(file.toPath())) {
            var iterator = walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .iterator();
            while (iterator.hasNext()) {
                var f = iterator.next();
                if (!f.delete()) {
                    throw new FileNotFoundException("Cannot delete: " + f.getAbsolutePath());
                }
                revokeDocumentPermission(getDocIdForFile(f));
            }
            notifyFileChange();
        } catch (IOException e) {
            throw handleIOExceptionFromFilesWalk(e, file);
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        return getMimeType(file);
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId, String targetParentDocumentId) throws FileNotFoundException {
        var srcFile = getFileForDocId(sourceDocumentId);
        var destFile = new File(getFileForDocId(targetParentDocumentId), srcFile.getName());
        if (destFile.exists()) {
            throw new FileNotFoundException("File already exists: " + destFile);
        }
        var pathsToInvalidate = findAllPathsIn(srcFile);
        if (!srcFile.renameTo(destFile)) {
            throw new FileNotFoundException("Cannot rename " + srcFile.getAbsolutePath() + " to " + destFile.getAbsolutePath());
        }
        revokeDocumentsPermission(pathsToInvalidate);
        return getDocIdForFile(destFile);
    }


    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection) throws FileNotFoundException {
        var result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        setNotificationUri(result);
        var parent = getFileForDocId(rootId);

        // This example implementation searches file names for the query and doesn't rank search
        // results, so we can stop as soon as we find a sufficient number of matches.  Other
        // implementations might rank results and use other data about files, rather than the file
        // name, to produce a match.
        final LinkedList<File> pending = new LinkedList<>();
        pending.add(parent);

        final int MAX_SEARCH_RESULTS = 50;
        while (!pending.isEmpty() && result.getCount() < MAX_SEARCH_RESULTS) {
            final File file = pending.removeFirst();
            // Avoid directories outside the $HOME directory linked with symlinks (to avoid e.g. search
            // through the whole SD card).
            boolean isInsideHome;
            try {
                isInsideHome = file.getCanonicalPath().startsWith(AppStorage.requireSingleton().getHomePath());
            } catch (IOException e) {
                isInsideHome = true;
            }
            if (isInsideHome) {
                if (file.isDirectory()) {
                    Collections.addAll(pending, file.listFiles());
                } else {
                    if (file.getName().toLowerCase(Locale.ROOT).contains(query)) {
                        includeFile(result, null, file);
                    }
                }
            }
        }

        return result;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId);
    }

    /**
     * Get the document id given a file. This document id must be consistent across time as other
     * applications may save the ID and use it to reference documents later.
     * <p/>
     * The reverse of @{link #getFileForDocId}.
     */
    private static String getDocIdForFile(File file) {
        return file.getAbsolutePath();
    }

    /**
     * Get the file given a document id (the reverse of {@link #getDocIdForFile(File)}).
     */
    private static File getFileForDocId(String docId) throws FileNotFoundException {
        final File f = new File(docId);
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath() + " not found");
        return f;
    }

    private static String getMimeType(File file) {
        if (file.isDirectory()) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        } else {
            final String name = file.getName();
            final int lastDot = name.lastIndexOf('.');
            if (lastDot >= 0) {
                final String extension = name.substring(lastDot + 1).toLowerCase(Locale.ROOT);
                final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (mime != null) return mime;
            }
            return "application/octet-stream";
        }
    }

    /**
     * Add a representation of a file to a cursor.
     *
     * @param result the cursor to modify
     * @param docId  the document ID representing the desired file (may be null if given file)
     * @param file   the File object representing the desired file (may be null if given docID)
     */
    private void includeFile(MatrixCursor result, String docId, File file)
            throws FileNotFoundException {
        if (docId == null) {
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
        }

        int flags = 0;
        if (file.canWrite()) {
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_MOVE;
            if (file.isDirectory()) {
                flags |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
            } else {
                flags |= DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
            }
        }

        final String displayName = file.getName();
        final String mimeType = getMimeType(file);
        if (mimeType.startsWith("image/"))
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL;

        var row = result.newRow();
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId);
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.length());
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType);
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags);
        row.add(DocumentsContract.Document.COLUMN_ICON, R.mipmap.ic_launcher);
    }

    private static List<String> findAllPathsIn(File fileOrDirectory) throws FileNotFoundException {
        try (var walk = Files.walk(fileOrDirectory.toPath())) {
            return walk.map(f -> f.toAbsolutePath().toString()).collect(Collectors.toList());
        } catch (IOException e) {
            throw handleIOExceptionFromFilesWalk(e, fileOrDirectory);
        }
    }

    private static FileNotFoundException handleIOExceptionFromFilesWalk(IOException e, File walked) {
        var errorMessage = "Error walking: " + walked.getAbsolutePath();
        Log.e(TAG, errorMessage, e);
        return new FileNotFoundException(errorMessage);
    }

    private void revokeDocumentsPermission(List<String> paths) {
        for (var path : paths) {
            revokeDocumentPermission(path);
        }
        notifyFileChange();
    }

    private void notifyFileChange() {
        var updatedUri = DocumentsContract.buildChildDocumentsUri(C.STORAGE_PROVIDER_AUTHORITY, baseDir.getAbsolutePath());
        var context = getContext();
        if (context != null) {
            context.getContentResolver().notifyChange(updatedUri, null);
        }
    }

    public static boolean dirContainsFile(File dir, File file) {
        if (dir == null || file == null) return false;
        String dirPath = dir.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (dirPath.equals(filePath)) {
            return true;
        }
        if (!dirPath.endsWith("/")) {
            dirPath += "/";
        }
        return filePath.startsWith(dirPath);
    }

    protected final List<String> findDocumentPath(File parent, File doc)
            throws FileNotFoundException {
        if (!doc.exists()) {
            throw new FileNotFoundException(doc + " is not found.");
        }
        if (!dirContainsFile(parent, doc)) {
            throw new FileNotFoundException(doc + " is not found under " + parent);
        }
        List<String> path = new ArrayList<>();
        while (doc != null && dirContainsFile(parent, doc)) {
            path.add(0, getDocIdForFile(doc));

            doc = doc.getParentFile();
        }
        return path;
    }

    @Override
    public DocumentsContract.Path findDocumentPath(@Nullable String parentDocumentId, String childDocumentId) throws FileNotFoundException {
        final String rootId = (parentDocumentId == null) ? getDocIdForFile(baseDir) : null;
        if (parentDocumentId == null) {
            parentDocumentId = getDocIdForFile(baseDir);
        }
        final File parent = getFileForDocId(parentDocumentId);
        final File doc = getFileForDocId(childDocumentId);
        return new DocumentsContract.Path(rootId, findDocumentPath(parent, doc));
    }

}
