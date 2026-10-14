package com.sejio.calorapp;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * App logger. Every line goes to logcat with the tag `Pausa` and to a rotating file in filesDir
 * (logs/pausa.log, then pausa.1.log). Warnings, errors and sync events are also queued in
 * PausaDb and uploaded to POST /api/logs, so problems can be read without the phone at hand.
 * Never log tokens, credentials or banking data.
 */
final class PausaLog {
    static final String TAG = "Pausa";
    private static final long MAX_FILE = 512 * 1024;
    private static Context app;

    private PausaLog() {}

    static synchronized void init(Context context) {
        if (app == null) app = context.getApplicationContext();
    }

    static void d(String tag, String message) { write("debug", tag, message, null, null); }
    static void i(String tag, String message) { write("info", tag, message, null, null); }
    static void i(String tag, String message, JSONObject data) { write("info", tag, message, data, null); }
    static void w(String tag, String message) { write("warn", tag, message, null, null); }
    static void w(String tag, String message, Throwable error) { write("warn", tag, message, null, error); }
    static void e(String tag, String message, Throwable error) { write("error", tag, message, null, error); }

    /** Structured event: queued for upload when it is a warning, an error or part of the sync. */
    static void event(String level, String tag, String message, JSONObject data) { write(level, tag, message, data, null); }

    private static void write(String level, String tag, String message, JSONObject data, Throwable error) {
        String line = "[" + tag + "] " + message + (data == null ? "" : " " + data);
        switch (level) {
            case "debug": Log.d(TAG, line); break;
            case "info": Log.i(TAG, line); break;
            case "warn": Log.w(TAG, line, error); break;
            default: Log.e(TAG, line, error);
        }
        Context context = app;
        if (context == null) return;
        long now = System.currentTimeMillis();
        appendToFile(context, now, level, line, error);
        boolean upload = level.equals("warn") || level.equals("error") || (tag.equals("sync") && !level.equals("debug"));
        if (!upload) return;
        try {
            JSONObject payload = data == null ? new JSONObject() : new JSONObject(data.toString());
            if (error != null) payload.put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            PausaDb.get(context).queueLog(now, level, tag, message, payload.length() == 0 ? null : payload);
        } catch (Exception ignored) {
            // Logging must never break the caller.
        }
    }

    static File file(Context context) {
        return new File(new File(context.getFilesDir(), "logs"), "pausa.log");
    }

    private static synchronized void appendToFile(Context context, long now, String level, String line, Throwable error) {
        try {
            File file = file(context);
            File dir = file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return;
            if (file.length() > MAX_FILE) {
                File old = new File(dir, "pausa.1.log");
                if (old.exists() && !old.delete()) return;
                if (!file.renameTo(old)) return;
            }
            try (FileWriter out = new FileWriter(file, true)) {
                out.write(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date(now)) + " " + level.charAt(0) + " " + line + "\n");
                if (error != null) out.write("    " + Log.getStackTraceString(error).replace("\n", "\n    ") + "\n");
            }
        } catch (IOException ignored) {
            // A full disk must not crash the app.
        }
    }

    /** The last `bytes` of the log file, for the diagnostics screen. */
    static String tail(Context context, int bytes) {
        File file = file(context);
        if (!file.exists()) return "";
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, in.length() - bytes);
            in.seek(start);
            byte[] buffer = new byte[(int) (in.length() - start)];
            in.readFully(buffer);
            String text = new String(buffer, java.nio.charset.StandardCharsets.UTF_8);
            int newline = start > 0 ? text.indexOf('\n') : -1;
            return newline >= 0 ? text.substring(newline + 1) : text;
        } catch (IOException failure) {
            return "No se pudo leer el log: " + failure.getMessage();
        }
    }
}
