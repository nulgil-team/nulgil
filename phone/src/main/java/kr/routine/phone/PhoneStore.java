package kr.routine.phone;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/**
 * What this phone remembers, in the app's private storage: my name (shown to the driver), friends' invite codes
 * (newest first, at most 5) and the car linked to this phone. Codes end on the server after 12 hours; a closed code
 * is removed when sending to it fails.
 */
public final class PhoneStore {
    private static final int MAX_INVITES = 5;
    private final SharedPreferences prefs;

    public PhoneStore(Context context) { prefs = context.getSharedPreferences("nulgil_phone", Context.MODE_PRIVATE); }

    public String sender() { return prefs.getString("sender", ""); }
    public void setSender(String value) { prefs.edit().putString("sender", value == null ? "" : value.trim()).apply(); }

    public List<String> invites() {
        String raw = prefs.getString("invites", "");
        List<String> out = new ArrayList<>();
        for (String code : raw.split(",")) if (!code.isBlank()) out.add(code);
        return out;
    }

    public void addInvite(String code) {
        List<String> next = new ArrayList<>(invites());
        next.remove(code); next.add(0, code);
        while (next.size() > MAX_INVITES) next.remove(next.size() - 1);
        prefs.edit().putString("invites", String.join(",", next)).apply();
    }

    public void removeInvite(String code) {
        List<String> next = new ArrayList<>(invites());
        next.remove(code);
        prefs.edit().putString("invites", String.join(",", next)).apply();
    }

    public Links.CarLink car() {
        String id = prefs.getString("car_id", null), token = prefs.getString("car_token", null);
        return id == null || token == null ? null : new Links.CarLink(id, token);
    }

    public void setCar(Links.CarLink link) {
        if (link == null) prefs.edit().remove("car_id").remove("car_token").apply();
        else prefs.edit().putString("car_id", link.id()).putString("car_token", link.token()).apply();
    }
}
