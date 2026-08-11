package com.ashcastle.duckyslicer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BlockingExportProvider extends ContentProvider {
    public static final String AUTHORITY = "com.ashcastle.duckyslicer.test.blocking-export";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/export.gcode");
    public static final String METHOD_PREPARE = "prepare";
    public static final String METHOD_PREPARE_OPEN_BLOCK = "prepare_open_block";
    public static final String METHOD_PREPARE_FAILURE = "prepare_failure";
    public static final String METHOD_RELEASE = "release";
    public static final String METHOD_STATUS = "status";
    public static final String KEY_STARTED = "started";
    public static final String KEY_COMPLETED = "completed";
    public static final String KEY_DELETED = "deleted";
    public static final String KEY_BYTES = "bytes";
    public static final String KEY_SHA256 = "sha256";
    public static final String KEY_ERROR = "error";
    private static final long TIMEOUT_SECONDS = 20L;
    private static final int MODE_STREAM_BLOCK = 0;
    private static final int MODE_OPEN_BLOCK = 1;
    private static final int MODE_FAIL_OPEN = 2;
    private static volatile Session current = new Session(MODE_STREAM_BLOCK);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        return null;
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
        current.deleted = true;
        current.release.countDown();
        return 1;
    }

    @Override
    public synchronized Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_PREPARE.equals(method)) {
            reset(MODE_STREAM_BLOCK);
            return Bundle.EMPTY;
        }
        if (METHOD_PREPARE_OPEN_BLOCK.equals(method)) {
            reset(MODE_OPEN_BLOCK);
            return Bundle.EMPTY;
        }
        if (METHOD_PREPARE_FAILURE.equals(method)) {
            reset(MODE_FAIL_OPEN);
            return Bundle.EMPTY;
        }
        if (METHOD_RELEASE.equals(method)) {
            current.release.countDown();
            return Bundle.EMPTY;
        }
        if (METHOD_STATUS.equals(method)) {
            return current.toBundle();
        }
        throw new IllegalArgumentException("Unsupported blocking-export method");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return openFileInternal(uri, mode, null);
    }

    @Override
    public AssetFileDescriptor openAssetFile(
            Uri uri,
            String mode,
            CancellationSignal signal
    ) throws FileNotFoundException {
        ParcelFileDescriptor descriptor = openFileInternal(uri, mode, signal);
        return new AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    private static ParcelFileDescriptor openFileInternal(
            Uri uri,
            String mode,
            CancellationSignal signal
    ) throws FileNotFoundException {
        if (!mode.contains("w")) {
            throw new IllegalArgumentException("Blocking export provider is write-only");
        }
        if (signal != null) {
            signal.throwIfCanceled();
        }
        Session target = current;
        if (target.mode == MODE_FAIL_OPEN) {
            target.started = true;
            target.completed = true;
            target.error = "FileNotFoundException";
            throw new FileNotFoundException("Intentional export failure");
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
                    throw new FileNotFoundException("Blocking export was not released");
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
                throw new FileNotFoundException("Blocking export was interrupted");
            } finally {
                if (signal != null) {
                    signal.setOnCancelListener(null);
                }
            }
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(
                    () -> readExport(pipe[0], target),
                    "Ducky blocking export provider"
            ).start();
            return pipe[1];
        } catch (Exception error) {
            throw new IllegalStateException("Could not create blocking export pipe", error);
        }
    }

    private static void reset(int mode) {
        current.release.countDown();
        current = new Session(mode);
    }

    private static void readExport(ParcelFileDescriptor readSide, Session target) {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(readSide);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int first = input.read(buffer);
            if (first > 0) {
                bytes.write(buffer, 0, first);
            }
            target.started = true;
            if (!target.release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Blocking export was not released");
            }
            int read;
            while ((read = input.read(buffer)) >= 0) {
                bytes.write(buffer, 0, read);
            }
            byte[] payload = bytes.toByteArray();
            target.bytes = payload.length;
            target.sha256 = hex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception error) {
            target.error = error.getClass().getSimpleName();
        } finally {
            target.completed = true;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(String.format("%02x", value));
        }
        return output.toString();
    }

    private static final class Session {
        final int mode;
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean started;
        volatile boolean completed;
        volatile boolean deleted;
        volatile int bytes;
        volatile String sha256 = "";
        volatile String error = "";

        Session(int mode) {
            this.mode = mode;
        }

        Bundle toBundle() {
            Bundle value = new Bundle();
            value.putBoolean(KEY_STARTED, started);
            value.putBoolean(KEY_COMPLETED, completed);
            value.putBoolean(KEY_DELETED, deleted);
            value.putInt(KEY_BYTES, bytes);
            value.putString(KEY_SHA256, sha256);
            value.putString(KEY_ERROR, error);
            return value;
        }
    }
}
