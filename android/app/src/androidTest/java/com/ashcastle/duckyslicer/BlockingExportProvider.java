package com.ashcastle.duckyslicer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BlockingExportProvider extends ContentProvider {
    public static final String AUTHORITY = "com.ashcastle.duckyslicer.test.blocking-export";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/export.gcode");
    public static final String METHOD_PREPARE = "prepare";
    public static final String METHOD_RELEASE = "release";
    public static final String METHOD_STATUS = "status";
    public static final String KEY_STARTED = "started";
    public static final String KEY_COMPLETED = "completed";
    public static final String KEY_DELETED = "deleted";
    public static final String KEY_BYTES = "bytes";
    public static final String KEY_SHA256 = "sha256";
    public static final String KEY_ERROR = "error";
    private static final long TIMEOUT_SECONDS = 20L;
    private static volatile Session current = new Session();

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
            current.release.countDown();
            current = new Session();
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
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
        if (!mode.contains("w")) {
            throw new IllegalArgumentException("Blocking export provider is write-only");
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Session target = current;
            new Thread(
                    () -> readExport(pipe[0], target),
                    "Ducky blocking export provider"
            ).start();
            return pipe[1];
        } catch (Exception error) {
            throw new IllegalStateException("Could not create blocking export pipe", error);
        }
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
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean started;
        volatile boolean completed;
        volatile boolean deleted;
        volatile int bytes;
        volatile String sha256 = "";
        volatile String error = "";

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
