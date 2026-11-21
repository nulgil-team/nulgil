package kr.routine.app.phone;

import android.content.Context;
import android.content.SharedPreferences;
import kr.routine.core.PhoneLink;

/** Keeps the linked phone (link id and the car's token) in the app's private storage. The phone's token is never kept. */
public final class PrefsPhoneLinkStore implements PhoneLink.Store {
    private final SharedPreferences preferences;

    public PrefsPhoneLinkStore(Context context) { preferences = context.getSharedPreferences("phone_link", Context.MODE_PRIVATE); }

    @Override public PhoneLink.Link load() {
        String id = preferences.getString("id", null), token = preferences.getString("token", null);
        if (id == null || token == null) return null;
        try { return new PhoneLink.Link(id, token); } catch (IllegalArgumentException damaged) { clear(); return null; }
    }

    @Override public void save(PhoneLink.Link link) { preferences.edit().putString("id", link.id()).putString("token", link.carToken()).apply(); }

    @Override public void clear() { preferences.edit().clear().apply(); }
}
