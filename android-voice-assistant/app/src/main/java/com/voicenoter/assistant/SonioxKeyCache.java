package com.voicenoter.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executor;

public final class SonioxKeyCache {
    private static final String KEY_API_KEY = "soniox_temp_api_key";
    private static final String KEY_EXPIRES_AT = "soniox_temp_expires_at";
    /** Refresh if less than 5 minutes remain before expiry. */
    private static final long REFRESH_BUFFER_MS = 5 * 60 * 1000L;
    private static final long DEFAULT_TTL_MS = 55 * 60 * 1000L;

    private SonioxKeyCache() {
    }

    static String getValidKey(Context context) {
        SharedPreferences prefs = prefs(context);
        String key = prefs.getString(KEY_API_KEY, "").trim();
        if (key.isEmpty()) return null;
        long expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L);
        if (expiresAt > 0 && System.currentTimeMillis() >= expiresAt - REFRESH_BUFFER_MS) {
            return null;
        }
        return key;
    }

    static void save(Context context, String apiKey, String expiresAtIso) {
        if (apiKey == null || apiKey.trim().isEmpty()) return;
        prefs(context).edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .putLong(KEY_EXPIRES_AT, parseExpiresAtMs(expiresAtIso))
            .apply();
    }

    static void clear(Context context) {
        prefs(context).edit()
            .remove(KEY_API_KEY)
            .remove(KEY_EXPIRES_AT)
            .apply();
    }

    static void prefetch(Context context, String baseUrl, Executor executor) {
        if (context == null || baseUrl == null || baseUrl.isEmpty()) return;
        if (getValidKey(context) != null) return;
        executor.execute(() -> {
            try {
                fetchAndCache(context, baseUrl);
            } catch (Exception ignored) {
            }
        });
    }

    static String fetchAndCache(Context context, String baseUrl) throws Exception {
        KeyResult result = fetchTemporaryKey(baseUrl);
        save(context, result.apiKey, result.expiresAtIso);
        return result.apiKey;
    }

    static KeyResult fetchTemporaryKey(String baseUrl) throws Exception {
        String endpoint = baseUrl;
        while (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint + "/api/soniox-key").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write("{}".getBytes(StandardCharsets.UTF_8));
        }

        int code = connection.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
            code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(),
            StandardCharsets.UTF_8
        ));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) response.append(line);

        if (code < 200 || code >= 300) {
            throw new Exception("Soniox key HTTP " + code);
        }

        JSONObject json = new JSONObject(response.toString());
        String apiKey = json.optString("api_key", "").trim();
        if (apiKey.isEmpty()) {
            throw new Exception("Missing Soniox API key");
        }
        return new KeyResult(apiKey, json.optString("expires_at", null));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    private static long parseExpiresAtMs(String expiresAtIso) {
        if (expiresAtIso == null || expiresAtIso.trim().isEmpty()) {
            return System.currentTimeMillis() + DEFAULT_TTL_MS;
        }
        try {
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            String value = expiresAtIso.trim();
            if (value.endsWith("Z")) {
                value = value.substring(0, value.length() - 1);
            } else if (value.contains("+")) {
                value = value.substring(0, value.indexOf('+'));
            } else {
                int dot = value.indexOf('.');
                if (dot > 0) value = value.substring(0, dot);
            }
            Date parsed = iso.parse(value);
            if (parsed != null) return parsed.getTime();
        } catch (Exception ignored) {
        }
        return System.currentTimeMillis() + DEFAULT_TTL_MS;
    }

    static final class KeyResult {
        final String apiKey;
        final String expiresAtIso;

        KeyResult(String apiKey, String expiresAtIso) {
            this.apiKey = apiKey;
            this.expiresAtIso = expiresAtIso;
        }
    }
}
