package kr.routine.app.invite;

import android.content.Context;
import android.content.SharedPreferences;
import kr.routine.core.Invite.Trip;
import kr.routine.core.InviteService;

/** Keeps the open invite (code, car token, end time) in the app's private storage across restarts. */
public final class PrefsTripStore implements InviteService.TripStore {
    private final SharedPreferences preferences;

    public PrefsTripStore(Context context, String name) {
        preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    @Override public Trip load() {
        String code = preferences.getString("code", null), token = preferences.getString("token", null);
        long expiresAt = preferences.getLong("expiresAt", 0);
        if (code == null || token == null) return null;
        try { return new Trip(code, token, expiresAt); } catch (IllegalArgumentException damaged) { clear(); return null; }
    }

    @Override public void save(Trip trip) {
        preferences.edit().putString("code", trip.code()).putString("token", trip.carToken())
            .putLong("expiresAt", trip.expiresAt()).apply();
    }

    @Override public void clear() { preferences.edit().clear().apply(); }
}
