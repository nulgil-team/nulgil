package kr.routine.phone;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import kr.routine.app.net.SupabaseRpc;
import kr.routine.core.NaviGateway.Callback;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The phone app's server calls. Same server and functions as the web pages:
 * resolve-place (shared text → place), submit_invite_suggestion (friend's car), send_phone_route (my car).
 * Sends only the chosen place and the sender's name; never this phone's location.
 */
public final class Server {
    /** OK: one place. CHOOSE: the person picks one of the candidates. NOT_FOUND: nothing usable. */
    public record Resolved(String status, List<Place> candidates) {}

    private final SupabaseRpc rpc;

    public Server(Context context) {
        rpc = new SupabaseRpc(context.getString(R.string.server_url), context.getString(R.string.server_key));
    }

    public void resolve(String text, Callback<Resolved> callback) {
        rpc.edge("resolve-place", SupabaseRpc.args("text", text), callback, body -> {
            JSONObject root = new JSONObject(body);
            List<Place> out = new ArrayList<>();
            JSONArray rows = root.optJSONArray("candidates");
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject p = rows.getJSONObject(i);
                try {
                    out.add(new Place(p.getString("name"), p.optString("address", ""), p.getDouble("latitude"),
                        p.getDouble("longitude"), p.optString("source", "TEXT")));
                } catch (IllegalArgumentException skipped) { /* outside Korea or unnamed */ }
            }
            return new Resolved(root.optString("status", "NOT_FOUND"), out);
        });
    }

    /** True when the invite is still open (friends can still send). */
    public void inviteOpen(String code, Callback<Boolean> callback) {
        rpc.call("get_invite_trip", SupabaseRpc.args("p_code", code), callback,
            body -> new JSONArray(body).length() > 0 && new JSONArray(body).getJSONObject(0).optBoolean("open", false));
    }

    public void sendToInvite(String code, String sender, Place place, String note, Callback<String> callback) {
        rpc.call("submit_invite_suggestion", SupabaseRpc.args("p_code", code, "p_sender", sender, "p_place_name", place.name(),
            "p_place_address", place.address(), "p_latitude", place.latitude(), "p_longitude", place.longitude(),
            "p_note", note == null ? "" : note), callback, body -> SupabaseRpc.firstRow(body).getString("id"));
    }

    public void sendToCar(Links.CarLink car, Place place, Callback<String> callback) {
        rpc.call("send_phone_route", SupabaseRpc.args("p_link", car.id(), "p_phone_token", car.token(), "p_place_name", place.name(),
            "p_place_address", place.address(), "p_latitude", place.latitude(), "p_longitude", place.longitude()),
            callback, body -> body.replace("\"", "").trim());
    }

    /** Readable message for a server error code (the code comes first in the error). */
    public static String message(String error) {
        if (error == null) return "잠시 뒤 다시 시도해 주세요.";
        if (error.startsWith("TRIP_CLOSED")) return "이 초대는 끝났어요. 친구에게 새 초대를 부탁해 주세요.";
        if (error.startsWith("TOO_MANY_SUGGESTIONS")) return "이 이동에는 더 보낼 수 없어요(최대 10곳).";
        if (error.startsWith("TOO_FAST")) return "한꺼번에 너무 많이 보냈어요. 조금 뒤 다시 보내 주세요.";
        if (error.startsWith("NOT_ALLOWED")) return "차와의 연결이 끊겼어요. 차 화면에서 다시 연결해 주세요.";
        return "인터넷 연결을 확인하고 다시 시도해 주세요.";
    }
}
