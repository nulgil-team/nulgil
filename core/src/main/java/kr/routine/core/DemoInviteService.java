package kr.routine.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import kr.routine.core.Invite.*;
import kr.routine.core.NaviGateway.Callback;

/** Synchronous stand-in for the invite server: invented friends and public places, no network. */
public final class DemoInviteService implements InviteService {
    public static final String CODE = "DEMO24";
    private final LongSupplier clock;
    private final Map<String, Suggestion> suggestions = new LinkedHashMap<>();
    private boolean open, failNext;
    private int created, closed;
    private final List<String> writes = new ArrayList<>();

    public DemoInviteService(LongSupplier clock) { this.clock = clock; }

    public void setFailNext(boolean value) { failNext = value; }
    public int created() { return created; }
    public int closed() { return closed; }
    /** "id=STATUS" in the order they were written. */
    public List<String> writes() { return List.copyOf(writes); }

    /** A friend sends a place (demo and checks). */
    public Suggestion receive(String id, String sender, String name, String address, double lat, double lon, String note) {
        Suggestion s = new Suggestion(id, sender, name, address, lat, lon, note, Status.PENDING, clock.getAsLong() + suggestions.size());
        suggestions.put(id, s);
        return s;
    }

    @Override public void createTrip(Callback<Trip> callback) {
        if (fail(callback)) return;
        created++; open = true; suggestions.clear();
        // Invented friends and public places near the demo route; never real messages.
        receive("demo-1", "민지", "판교역 빵집", "경기도 성남시 분당구 판교역로 160", 37.3947, 127.1112, "빵 사 갈게");
        receive("demo-2", "준호", "정자동 카페거리", "경기도 성남시 분당구 정자일로 95", 37.3662, 127.1065, "");
        callback.onSuccess(new Trip(CODE, "demo-car-token", clock.getAsLong() + 12 * 60 * 60_000L));
    }

    @Override public void listSuggestions(Trip trip, Callback<List<Suggestion>> callback) {
        if (fail(callback)) return;
        if (!open) { callback.onFailure("TRIP_CLOSED: 초대가 끝났어요."); return; }
        callback.onSuccess(List.copyOf(suggestions.values()));
    }

    @Override public void setStatus(Trip trip, String id, Status status, Callback<Void> callback) {
        if (fail(callback)) return;
        Suggestion s = suggestions.get(id);
        if (s == null) { callback.onFailure("NOT_FOUND"); return; }
        writes.add(id + "=" + status);
        suggestions.put(id, new Suggestion(s.id(), s.sender(), s.placeName(), s.address(), s.latitude(), s.longitude(),
            s.note(), status, s.createdAt()));
        callback.onSuccess(null);
    }

    @Override public void closeTrip(Trip trip, Callback<Void> callback) { closed++; open = false; callback.onSuccess(null); }

    @Override public String inviteUrl(Trip trip) { return ""; }

    private boolean fail(Callback<?> callback) {
        if (!failNext) return false;
        failNext = false; callback.onFailure("모의 서버 응답 없음"); return true;
    }
}
