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
import android.provider.OpenableColumns;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BlockingImportProvider extends ContentProvider {
    public static final String AUTHORITY = "com.ashcastle.duckyslicer.test.blocking-import";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/project.duckyproject");
    public static final Uri MODEL_URI = Uri.parse("content://" + AUTHORITY + "/blocked-model.stl");
    public static final String METHOD_PREPARE = "prepare";
    public static final String METHOD_PREPARE_OPEN_BLOCK = "prepare_open_block";
    public static final String METHOD_RELEASE = "release";
    public static final String METHOD_STATUS = "status";
    public static final String KEY_STARTED = "started";
    public static final String KEY_COMPLETED = "completed";
    public static final String KEY_BYTES = "bytes";
    public static final String KEY_ERROR = "error";
    public static final String KEY_SOURCE_DESCRIPTOR = "source_descriptor";
    private static final long TIMEOUT_SECONDS = 120L;
    private static final int MODE_STREAM = 0;
    private static final int MODE_OPEN_BLOCK = 1;
    private static volatile Session current = new Session(MODE_STREAM, null);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return MODEL_URI.equals(uri)
                ? "model/stl"
                : "application/vnd.duckyslicer.project+zip";
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(MODEL_URI.equals(uri) ? "blocked-model.stl" : "project.duckyproject");
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(current.source == null ? null : current.source.getStatSize());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs
    ) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public synchronized Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_PREPARE.equals(method)) {
            reset(MODE_STREAM, duplicateSource(extras));
            return Bundle.EMPTY;
        }
        if (METHOD_PREPARE_OPEN_BLOCK.equals(method)) {
            reset(MODE_OPEN_BLOCK, duplicateSource(extras));
            return Bundle.EMPTY;
        }
        if (METHOD_RELEASE.equals(method)) {
            current.release.countDown();
            return Bundle.EMPTY;
        }
        if (METHOD_STATUS.equals(method)) {
            return current.toBundle();
        }
        throw new IllegalArgumentException("Unsupported blocking-import method");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        AssetFileDescriptor descriptor = openAssetFile(uri, mode, null);
        return descriptor.getParcelFileDescriptor();
    }

    @Override
    public AssetFileDescriptor openAssetFile(
            Uri uri,
            String mode,
            CancellationSignal signal
    ) throws FileNotFoundException {
        if (!mode.contains("r")) {
            throw new IllegalArgumentException("Blocking import provider is read-only");
        }
        if (signal != null) {
            signal.throwIfCanceled();
        }
        Session target = current;
        if (target.source == null || !target.source.getFileDescriptor().valid()) {
            throw new FileNotFoundException("Import source is unavailable");
        }
        if (target.mode == MODE_OPEN_BLOCK) {
            target.started = true;
            if (signal != null) {
                signal.setOnCancelListener(target.release::countDown);
            }
            try {
                if (!target.release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    target.error = "OpenTimeout";
                    target.completed = true;
                    throw new FileNotFoundException("Blocking import was not released");
                }
                if (signal != null) {
                    signal.throwIfCanceled();
                }
            } catch (OperationCanceledException error) {
                target.error = "OperationCanceledException";
                target.completed = true;
                throw error;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                target.error = "InterruptedException";
                target.completed = true;
                throw new FileNotFoundException("Blocking import was interrupted");
            } finally {
                if (signal != null) {
                    signal.setOnCancelListener(null);
                }
            }
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            if (signal != null) {
                signal.setOnCancelListener(target.release::countDown);
            }
            new Thread(
                    () -> streamArchive(pipe[1], target, signal),
                    "Ducky blocking import provider"
            ).start();
            return new AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (Exception error) {
            throw new IllegalStateException("Could not create blocking import pipe", error);
        }
    }

    @SuppressWarnings("deprecation")
    private static ParcelFileDescriptor duplicateSource(Bundle extras) {
        ParcelFileDescriptor received = extras == null
                ? null
                : (ParcelFileDescriptor) extras.getParcelable(KEY_SOURCE_DESCRIPTOR);
        if (received == null) {
            throw new IllegalArgumentException("Import source descriptor is required");
        }
        try {
            return ParcelFileDescriptor.dup(received.getFileDescriptor());
        } catch (IOException error) {
            throw new IllegalArgumentException("Import source is unavailable", error);
        }
    }

    private static void reset(int mode, ParcelFileDescriptor source) {
        current.release.countDown();
        if (current.source != null) {
            try {
                current.source.close();
            } catch (IOException ignored) {
                // The previous test session is already terminal.
            }
        }
        current = new Session(mode, source);
    }

    private static void streamArchive(
            ParcelFileDescriptor writeSide,
            Session target,
            CancellationSignal signal
    ) {
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(target.source);
             OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(writeSide)) {
            byte[] buffer = new byte[8192];
            int count;
            boolean awaitingCancellation = true;
            while ((count = input.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                output.write(buffer, 0, count);
                target.bytes += count;
                target.started = true;
                if (awaitingCancellation) {
                    awaitingCancellation = false;
                    if (!target.release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("Blocking import was not released");
                    }
                    if (signal != null) {
                        signal.throwIfCanceled();
                    }
                }
            }
        } catch (Exception error) {
            target.error = error.getClass().getSimpleName();
        } finally {
            if (signal != null) {
                signal.setOnCancelListener(null);
            }
            target.completed = true;
        }
    }

    private static final class Session {
        final int mode;
        final ParcelFileDescriptor source;
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean started;
        volatile boolean completed;
        volatile int bytes;
        volatile String error = "";

        Session(int mode, ParcelFileDescriptor source) {
            this.mode = mode;
            this.source = source;
        }

        Bundle toBundle() {
            Bundle value = new Bundle();
            value.putBoolean(KEY_STARTED, started);
            value.putBoolean(KEY_COMPLETED, completed);
            value.putInt(KEY_BYTES, bytes);
            value.putString(KEY_ERROR, error);
            return value;
        }
    }
}
