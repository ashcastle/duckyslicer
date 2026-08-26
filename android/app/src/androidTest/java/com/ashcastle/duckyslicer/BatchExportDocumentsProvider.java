package com.ashcastle.duckyslicer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test-only provider implementing the DocumentsContract calls used by batch export. */
public final class BatchExportDocumentsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.ashcastle.duckyslicer.test.batch-export";
    public static final String ROOT_ID = "root";
    public static final Uri TREE_URI = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID);
    public static final String METHOD_PREPARE_SUCCESS = "prepare_success";
    public static final String METHOD_PREPARE_FAIL_SECOND = "prepare_fail_second";
    public static final String METHOD_PREPARE_BLOCK_SECOND = "prepare_block_second";
    public static final String METHOD_RELEASE = "release";
    public static final String METHOD_STATUS = "status";
    public static final String KEY_CREATED = "created";
    public static final String KEY_SECOND_OPEN_STARTED = "second_open_started";
    public static final String KEY_FILES = "files";
    public static final String KEY_CONTENTS = "contents";
    private static final String METHOD_CREATE_DOCUMENT = "android:createDocument";
    private static final String METHOD_DELETE_DOCUMENT = "android:deleteDocument";
    private static final String EXTRA_URI = "uri";
    private static final int MODE_SUCCESS = 0;
    private static final int MODE_FAIL_SECOND = 1;
    private static final int MODE_BLOCK_SECOND = 2;
    private static final long TIMEOUT_SECONDS = 120L;
    private static volatile Session current = new Session(MODE_SUCCESS);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public synchronized Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_CREATE_DOCUMENT.equals(method)) {
            Uri parent = extras.getParcelable(EXTRA_URI);
            String mimeType = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE);
            String displayName = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            try {
                String documentId = createDocument(parent, mimeType, displayName);
                Bundle result = new Bundle();
                result.putParcelable(
                        EXTRA_URI,
                        DocumentsContract.buildDocumentUriUsingTree(parent, documentId)
                );
                return result;
            } catch (FileNotFoundException error) {
                throw new IllegalStateException(error);
            }
        }
        if (METHOD_DELETE_DOCUMENT.equals(method)) {
            Uri documentUri = extras.getParcelable(EXTRA_URI);
            try {
                deleteDocument(documentUri);
                return Bundle.EMPTY;
            } catch (FileNotFoundException error) {
                throw new IllegalStateException(error);
            }
        }
        if (METHOD_PREPARE_SUCCESS.equals(method)) {
            reset(MODE_SUCCESS);
            return Bundle.EMPTY;
        }
        if (METHOD_PREPARE_FAIL_SECOND.equals(method)) {
            reset(MODE_FAIL_SECOND);
            return Bundle.EMPTY;
        }
        if (METHOD_PREPARE_BLOCK_SECOND.equals(method)) {
            reset(MODE_BLOCK_SECOND);
            return Bundle.EMPTY;
        }
        if (METHOD_RELEASE.equals(method)) {
            current.release.countDown();
            return Bundle.EMPTY;
        }
        if (METHOD_STATUS.equals(method)) {
            Bundle status = new Bundle();
            status.putInt(KEY_CREATED, current.created);
            status.putBoolean(KEY_SECOND_OPEN_STARTED, current.secondOpenStarted);
            ArrayList<String> files = new ArrayList<>();
            ArrayList<String> contents = new ArrayList<>();
            File[] children = root().listFiles();
            if (children != null) {
                Arrays.sort(children, Comparator.comparing(File::getName));
                for (File child : children) {
                    files.add(child.getName());
                    try {
                        contents.add(new String(
                                Files.readAllBytes(child.toPath()),
                                StandardCharsets.UTF_8
                        ));
                    } catch (IOException error) {
                        throw new IllegalStateException(error);
                    }
                }
            }
            status.putStringArrayList(KEY_FILES, files);
            status.putStringArrayList(KEY_CONTENTS, contents);
            return status;
        }
        return super.call(method, arg, extras);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!mode.contains("w")) throw new FileNotFoundException("Write-only provider");
        File targetFile = document(DocumentsContract.getDocumentId(uri));
        Session target = current;
        if (target.mode == MODE_BLOCK_SECOND && target.created >= 2) {
            target.secondOpenStarted = true;
            if (signal != null) signal.setOnCancelListener(target.release::countDown);
            try {
                if (!target.release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new FileNotFoundException("Blocked document timed out");
                }
                if (signal != null) signal.throwIfCanceled();
            } catch (OperationCanceledException error) {
                throw error;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new FileNotFoundException("Blocked document interrupted");
            } finally {
                if (signal != null) signal.setOnCancelListener(null);
            }
        }
        return ParcelFileDescriptor.open(
                targetFile,
                ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE
        );
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        return new AssetFileDescriptor(
                openFile(uri, mode, signal),
                0,
                AssetFileDescriptor.UNKNOWN_LENGTH
        );
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        return new MatrixCursor(projection == null ? new String[0] : projection);
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Insert not supported");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        try {
            deleteDocument(uri);
            return 1;
        } catch (FileNotFoundException error) {
            return 0;
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Update not supported");
    }

    private String createDocument(Uri parent, String mimeType, String displayName)
            throws FileNotFoundException {
        if (parent == null || !AUTHORITY.equals(parent.getAuthority())
                || !ROOT_ID.equals(DocumentsContract.getDocumentId(parent))) {
            throw new FileNotFoundException("Unknown parent");
        }
        if (!"application/octet-stream".equals(mimeType)) {
            throw new FileNotFoundException("Unexpected MIME type");
        }
        Session target = current;
        int number;
        synchronized (target) {
            number = ++target.created;
        }
        if (target.mode == MODE_FAIL_SECOND && number == 2) {
            throw new FileNotFoundException("Intentional second document failure");
        }
        File output = documentFile(displayName);
        try {
            if (!output.createNewFile()) throw new FileNotFoundException("Duplicate document");
        } catch (java.io.IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
        return displayName;
    }

    private void deleteDocument(Uri documentUri) throws FileNotFoundException {
        File target = document(DocumentsContract.getDocumentId(documentUri));
        if (target.exists() && !target.delete()) throw new FileNotFoundException("Delete failed");
    }

    private void reset(int mode) {
        current.release.countDown();
        File[] children = root().listFiles();
        if (children != null) {
            for (File child : children) child.delete();
        }
        current = new Session(mode);
    }

    private File root() {
        File directory = new File(getContext().getCacheDir(), "batch-export-provider");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create batch-export provider root");
        }
        return directory;
    }

    private File document(String documentId) throws FileNotFoundException {
        File file = documentFile(documentId);
        if (!file.isFile()) throw new FileNotFoundException("Unknown document");
        return file;
    }

    private File documentFile(String documentId) throws FileNotFoundException {
        if (documentId == null || documentId.trim().isEmpty() || ROOT_ID.equals(documentId)
                || documentId.contains("..") || documentId.contains("/")
                || documentId.contains("\\")) {
            throw new FileNotFoundException("Unsafe document id");
        }
        for (int index = 0; index < documentId.length(); index++) {
            if (Character.isISOControl(documentId.charAt(index))) {
                throw new FileNotFoundException("Unsafe document id");
            }
        }
        try {
            Path rootPath = root().toPath().toAbsolutePath().normalize();
            Path candidate = rootPath.resolve(documentId).normalize();
            if (!candidate.startsWith(rootPath) || !rootPath.equals(candidate.getParent())) {
                throw new FileNotFoundException("Unsafe document id");
            }
            return candidate.toFile();
        } catch (InvalidPathException error) {
            throw new FileNotFoundException("Unsafe document id");
        }
    }

    private static final class Session {
        final int mode;
        final CountDownLatch release = new CountDownLatch(1);
        volatile int created;
        volatile boolean secondOpenStarted;

        Session(int mode) {
            this.mode = mode;
        }
    }
}
