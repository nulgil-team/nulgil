package kr.routine.app.net;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.routine.core.NaviGateway.Callback;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Supabase transport shared by the car app and the phone app: database functions (RPC) and Edge Functions.
 * One background thread, callbacks on the main thread. Server errors keep their code first (TRIP_CLOSED, ...).
 */
public final class SupabaseRpc {
    private static final int TIMEOUT_MS = 8000;
    private final String baseUrl, apiKey;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public interface Parser<T> { T parse(String body) throws JSONException; }

    public SupabaseRpc(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    public <T> void call(String function, JSONObject args, Callback<T> callback, Parser<T> parser) {
        send("/rest/v1/rpc/" + function, args, callback, parser);
    }

    /** Supabase Edge Function (functions/v1/&lt;name&gt;), same callback rules. */
    public <T> void edge(String function, JSONObject body, Callback<T> callback, Parser<T> parser) {
        send("/functions/v1/" + function, body, callback, parser);
    }

    private <T> void send(String path, JSONObject args, Callback<T> callback, Parser<T> parser) {
        io.execute(() -> {
            T value; String error;
            try { value = parser.parse(post(path, args)); error = null; }
            catch (IOException | JSONException | RuntimeException failure) { value = null; error = describe(failure); }
            final T result = value; final String failure = error;
            main.post(() -> { if (failure == null) callback.onSuccess(result); else callback.onFailure(failure); });
        });
    }

    private String post(String path, JSONObject args) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS); connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("apikey", apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            try (OutputStream out = connection.getOutputStream()) { out.write(args.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = connection.getResponseCode();
            String body = read(code < 400 ? connection.getInputStream() : connection.getErrorStream());
            if (code >= 400) throw new IOException(serverMessage(body, code));
            return body;
        } finally { connection.disconnect(); }
    }

    private static String serverMessage(String body, int code) {
        try { String message = new JSONObject(body).optString("message", ""); if (!message.isEmpty()) return message; }
        catch (JSONException ignored) {}
        return "HTTP " + code;
    }

    private static String describe(Exception failure) {
        if (failure instanceof IOException && failure.getMessage() != null && !failure.getMessage().isBlank()) return failure.getMessage();
        return failure.getClass().getSimpleName();
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    // --- small JSON helpers for the services ---

    public static JSONObject firstRow(String body) throws JSONException {
        JSONArray rows = new JSONArray(body);
        if (rows.length() == 0) throw new JSONException("empty");
        return rows.getJSONObject(0);
    }

    public static JSONObject args(Object... keysAndValues) {
        JSONObject out = new JSONObject();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) put(out, (String) keysAndValues[i], keysAndValues[i + 1]);
        return out;
    }

    public static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    public static long millis(String timestamp) { return OffsetDateTime.parse(timestamp).toInstant().toEpochMilli(); }
}
