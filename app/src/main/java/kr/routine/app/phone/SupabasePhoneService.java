package kr.routine.app.phone;

import android.content.Context;
import kr.routine.app.R;
import kr.routine.app.net.SupabaseRpc;
import kr.routine.core.NaviGateway.Callback;
import kr.routine.core.PhoneLink.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 폰에서 경로 보내기 over Supabase RPC (server/supabase/migrations/20261121030000_phone_routes.sql). The car sends
 * only its link id and car token: never its location, destination or route.
 */
public final class SupabasePhoneService implements Service {
    private final SupabaseRpc rpc;
    private final String phoneUrl;

    public SupabasePhoneService(SupabaseRpc rpc, String phoneUrl) { this.rpc = rpc; this.phoneUrl = phoneUrl == null ? "" : phoneUrl.trim(); }

    /** From res/values/invite.xml (same server as friend stops); null when no server or phone page is configured. */
    public static SupabasePhoneService fromResources(Context context) {
        String url = context.getString(R.string.invite_api_url).trim(), key = context.getString(R.string.invite_api_key).trim();
        String page = context.getString(R.string.phone_web_url).trim();
        if (url.isEmpty() || key.isEmpty() || page.isEmpty()) return null;
        return new SupabasePhoneService(new SupabaseRpc(url, key), page);
    }

    @Override public void createLink(Callback<Pairing> callback) {
        rpc.call("create_phone_link", new JSONObject(), callback, body -> {
            JSONObject row = SupabaseRpc.firstRow(body);
            return new Pairing(new Link(row.getString("link_id"), row.getString("car_token")), row.getString("phone_token"));
        });
    }

    @Override public void take(Link link, Callback<Route> callback) {
        rpc.call("take_phone_route", owner(link), callback, body -> {
            JSONArray rows = new JSONArray(body);
            if (rows.length() == 0) return null;
            JSONObject r = rows.getJSONObject(0);
            return new Route(r.getString("id"), r.getString("place_name"), r.optString("place_address", ""),
                r.getDouble("latitude"), r.getDouble("longitude"), SupabaseRpc.millis(r.getString("created_at")));
        });
    }

    @Override public void revoke(Link link, Callback<Void> callback) { rpc.call("revoke_phone_link", owner(link), callback, body -> null); }

    @Override public String pairUrl(Pairing pairing) {
        if (phoneUrl.isEmpty()) return "";
        return phoneUrl + (phoneUrl.contains("?") ? "&" : "?") + "l=" + pairing.link().id() + "&k=" + pairing.phoneToken();
    }

    private static JSONObject owner(Link link) { return SupabaseRpc.args("p_link", link.id(), "p_car_token", link.carToken()); }
}
