package kr.routine.app.invite;

import android.content.Context;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.routine.app.R;
import kr.routine.core.Invite.*;
import kr.routine.core.InviteService;
import kr.routine.core.NaviGateway.Callback;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Invite server over Supabase RPC (server/supabase/migrations). Network on one background thread, callbacks on the
 * main thread. Sends only the invite code, its car token and status changes: never the car's location or route.
 */
public final class SupabaseInviteService implements InviteService {
    private static final int TIMEOUT_MS = 8000;
    private final String baseUrl, apiKey, webUrl;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public SupabaseInviteService(String baseUrl, String apiKey, String webUrl) {
        this.baseUrl = trimSlash(baseUrl); this.apiKey = apiKey; this.webUrl = webUrl == null ? "" : webUrl.trim();
    }

    /** From res/values/invite.xml; null when no server is configured. */
    public static SupabaseInviteService fromResources(Context context) {
        String url = context.getString(R.string.invite_api_url).trim(), key = context.getString(R.string.invite_api_key).trim();
        if (url.isEmpty() || key.isEmpty()) return null;
        return new SupabaseInviteService(url, key, context.getString(R.string.invite_web_url));
    }

    @Override public void createTrip(Callback<Trip> callback) {
        call("create_invite_trip", new JSONObject(), callback, body -> {
            JSONObject row = firstRow(body);
            return new Trip(row.getString("code"), row.getString("car_token"), millis(row.getString("expires_at")));
        });
    }

    @Override public void listSuggestions(Trip trip, Callback<List<Suggestion>> callback) {
        call("list_invite_suggestions", owner(trip), callback, body -> {
            JSONArray rows = new JSONArray(body);
            List<Suggestion> out = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                Status status;
                try { status = Status.valueOf(r.getString("status")); } catch (IllegalArgumentException unknown) { continue; }
                out.add(new Suggestion(r.getString("id"), r.optString("sender", ""), r.getString("place_name"),
                    r.optString("place_address", ""), r.getDouble("latitude"), r.getDouble("longitude"),
                    r.isNull("note") ? "" : r.optString("note", ""), status,
                    r.has("created_at") ? millis(r.getString("created_at")) : i));
            }
            return out;
        });
    }

    @Override public void setStatus(Trip trip, String id, Status status, Callback<Void> callback) {
        JSONObject args = owner(trip);
        put(args, "p_id", id); put(args, "p_status", status.name());
        call("set_invite_suggestion_status", args, callback, body -> null);
    }

    @Override public void closeTrip(Trip trip, Callback<Void> callback) {
        call("close_invite_trip", owner(trip), callback, body -> null);
    }

    @Override public String inviteUrl(Trip trip) {
        if (webUrl.isEmpty() || trip == null) return "";
        return webUrl + (webUrl.contains("?") ? "&" : "?") + "c=" + trip.code();
    }

    // --- transport ---

    private interface Parser<T> { T parse(String body) throws JSONException; }

    private <T> void call(String function, JSONObject args, Callback<T> callback, Parser<T> parser) {
        io.execute(() -> {
            T value; String error;
            try { value = parser.parse(post(function, args)); error = null; }
            catch (IOException | JSONException | RuntimeException failure) { value = null; error = describe(failure); }
            final T result = value; final String failure = error;
            main.post(() -> { if (failure == null) callback.onSuccess(result); else callback.onFailure(failure); });
        });
    }

    private String post(String function, JSONObject args) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/rest/v1/rpc/" + function).openConnection();
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

    /** Server errors keep their code first (TRIP_CLOSED, NOT_ALLOWED, ...) so InviteInbox can react. */
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

    private static JSONObject firstRow(String body) throws JSONException {
        JSONArray rows = new JSONArray(body);
        if (rows.length() == 0) throw new JSONException("empty");
        return rows.getJSONObject(0);
    }

    private static JSONObject owner(Trip trip) {
        JSONObject args = new JSONObject();
        put(args, "p_code", trip.code()); put(args, "p_car_token", trip.carToken());
        return args;
    }

    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static long millis(String timestamp) { return OffsetDateTime.parse(timestamp).toInstant().toEpochMilli(); }

    private static String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
}
