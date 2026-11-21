package kr.routine.app.invite;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import kr.routine.app.R;
import kr.routine.app.net.SupabaseRpc;
import kr.routine.core.Invite.*;
import kr.routine.core.InviteService;
import kr.routine.core.NaviGateway.Callback;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Invite server over Supabase RPC (server/supabase/migrations). Transport in SupabaseRpc. Sends only the invite
 * code, its car token and status changes: never the car's location or route.
 */
public final class SupabaseInviteService implements InviteService {
    private final SupabaseRpc rpc;
    private final String webUrl;

    public SupabaseInviteService(SupabaseRpc rpc, String webUrl) {
        this.rpc = rpc; this.webUrl = webUrl == null ? "" : webUrl.trim();
    }

    /** From res/values/invite.xml; null when no server is configured. */
    public static SupabaseInviteService fromResources(Context context) {
        String url = context.getString(R.string.invite_api_url).trim(), key = context.getString(R.string.invite_api_key).trim();
        if (url.isEmpty() || key.isEmpty()) return null;
        return new SupabaseInviteService(new SupabaseRpc(url, key), context.getString(R.string.invite_web_url));
    }

    @Override public void createTrip(Callback<Trip> callback) {
        rpc.call("create_invite_trip", new JSONObject(), callback, body -> {
            JSONObject row = SupabaseRpc.firstRow(body);
            return new Trip(row.getString("code"), row.getString("car_token"), SupabaseRpc.millis(row.getString("expires_at")));
        });
    }

    @Override public void listSuggestions(Trip trip, Callback<List<Suggestion>> callback) {
        rpc.call("list_invite_suggestions", owner(trip), callback, body -> {
            JSONArray rows = new JSONArray(body);
            List<Suggestion> out = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                Status status;
                try { status = Status.valueOf(r.getString("status")); } catch (IllegalArgumentException unknown) { continue; }
                out.add(new Suggestion(r.getString("id"), r.optString("sender", ""), r.getString("place_name"),
                    r.optString("place_address", ""), r.getDouble("latitude"), r.getDouble("longitude"),
                    r.isNull("note") ? "" : r.optString("note", ""), status,
                    r.has("created_at") ? SupabaseRpc.millis(r.getString("created_at")) : i));
            }
            return out;
        });
    }

    @Override public void setStatus(Trip trip, String id, Status status, Callback<Void> callback) {
        JSONObject args = owner(trip);
        SupabaseRpc.put(args, "p_id", id); SupabaseRpc.put(args, "p_status", status.name());
        rpc.call("set_invite_suggestion_status", args, callback, body -> null);
    }

    @Override public void closeTrip(Trip trip, Callback<Void> callback) {
        rpc.call("close_invite_trip", owner(trip), callback, body -> null);
    }

    @Override public String inviteUrl(Trip trip) {
        if (webUrl.isEmpty() || trip == null) return "";
        return webUrl + (webUrl.contains("?") ? "&" : "?") + "c=" + trip.code();
    }

    private static JSONObject owner(Trip trip) { return SupabaseRpc.args("p_code", trip.code(), "p_car_token", trip.carToken()); }
}
