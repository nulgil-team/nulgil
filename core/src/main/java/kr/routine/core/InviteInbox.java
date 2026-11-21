package kr.routine.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.routine.core.Invite.*;
import kr.routine.core.NaviGateway.Callback;

/**
 * Car-side inbox of friend suggestions. UI-thread only. It never touches the navi: VehicleSession decides when a
 * suggestion becomes a stop and reports the outcome back here, which forwards it to the friend.
 */
public final class InviteInbox {
    /** Read interval while the driver has the friend screen open. */
    public static final long WATCH_POLL_MS = 15_000;
    /** Read interval on other screens; suggestions are chosen while parked, so a minute late costs nothing. */
    public static final long IDLE_POLL_MS = 60_000;
    private static final int HANDLED_SHOWN = 5;

    private final InviteService service;
    private final InviteService.TripStore store;
    private Trip trip;
    private List<Suggestion> suggestions = List.of();
    /** Statuses the car set; shown at once and kept until the server echoes them. */
    private final Map<String, Status> local = new HashMap<>();
    private final ArrayDeque<Runnable> writes = new ArrayDeque<>();
    private boolean opening, polling, writing, watching;
    private long lastPoll = Long.MIN_VALUE / 2, generation;
    private String message = "";

    public InviteInbox(InviteService service, InviteService.TripStore store, long now) {
        this.service = Objects.requireNonNull(service);
        this.store = Objects.requireNonNull(store);
        Trip saved = null;
        try { saved = store.load(); } catch (RuntimeException unreadable) { store.clear(); }
        if (saved != null && saved.open(now)) trip = saved; else if (saved != null) store.clear();
    }

    /** Creates an invite code. No-op while one is open or being created. */
    public void open(long now, Runnable changed) {
        if (opening || isOpen(now)) return;
        opening = true; message = "초대 코드를 만들고 있어요";
        long gen = ++generation;
        changed.run();
        service.createTrip(new Callback<>() {
            @Override public void onSuccess(Trip value) {
                if (gen != generation) return;
                opening = false;
                if (value == null) { message = "초대 코드를 만들지 못했어요."; changed.run(); return; }
                trip = value; suggestions = List.of(); local.clear(); message = "";
                try { store.save(value); } catch (RuntimeException ignored) { message = "초대 코드를 이 기기에 저장하지 못했어요."; }
                lastPoll = now;
                changed.run();
            }
            @Override public void onFailure(String error) {
                if (gen != generation) return;
                opening = false; message = "초대 코드를 만들지 못했어요. 인터넷 연결을 확인해 주세요."; changed.run();
            }
        });
    }

    /** True while the friend screen is shown; reads then come every WATCH_POLL_MS instead of IDLE_POLL_MS. */
    public void setWatching(boolean value) { watching = value; }
    public long pollInterval() { return watching ? WATCH_POLL_MS : IDLE_POLL_MS; }

    /** Reads suggestions at most every pollInterval() unless forced. Ends an invite the server closed or that expired. */
    public void poll(long now, boolean force, Runnable changed) {
        if (trip == null || polling) return;
        if (!trip.open(now)) { end("초대 시간이 끝났어요."); changed.run(); return; }
        if (!force && now - lastPoll < pollInterval()) return;
        polling = true; lastPoll = now;
        long gen = generation;
        Trip asked = trip;
        service.listSuggestions(asked, new Callback<>() {
            @Override public void onSuccess(List<Suggestion> values) {
                if (gen != generation) return;
                polling = false;
                List<Suggestion> before = suggestions; String beforeMessage = message;
                merge(values == null ? List.of() : values);
                if (message.startsWith("친구 제안을 확인하지")) message = "";
                // Redraw only when something the driver sees changed; polling runs every few seconds.
                if (!suggestions.equals(before) || !message.equals(beforeMessage)) changed.run();
            }
            @Override public void onFailure(String error) {
                if (gen != generation) return;
                polling = false;
                if (error != null && (error.startsWith("TRIP_CLOSED") || error.startsWith("NOT_ALLOWED"))) end("초대가 끝났어요.");
                else message = "친구 제안을 확인하지 못했어요. 잠시 뒤 다시 확인해요.";
                changed.run();
            }
        });
    }

    private void merge(List<Suggestion> values) {
        List<Suggestion> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.comparingLong(Suggestion::createdAt));
        suggestions = List.copyOf(sorted);
        for (Suggestion s : suggestions) if (local.get(s.id()) == s.status()) local.remove(s.id());
    }

    /**
     * Records the car's decision for the friend. Writes go out one at a time in order, so REQUESTED always
     * reaches the server before the final result.
     */
    public void setStatus(String id, Status next, Runnable changed) {
        if (trip == null || find(id) == null || next == null) return;
        local.put(id, next);
        Trip target = trip; long gen = generation;
        writes.add(() -> service.setStatus(target, id, next, new Callback<>() {
            @Override public void onSuccess(Void ignored) { if (gen == generation) written(changed); }
            @Override public void onFailure(String error) {
                if (gen != generation) return;
                message = "친구에게 결과를 알리지 못했어요. 차에서는 그대로 진행돼요.";
                written(changed);
            }
        }));
        changed.run();
        drain();
    }

    private void written(Runnable changed) { writing = false; changed.run(); drain(); }

    private void drain() {
        if (writing || writes.isEmpty()) return;
        writing = true;
        Runnable next = writes.poll();
        try { next.run(); } catch (RuntimeException failure) { writing = false; message = "친구에게 결과를 알리지 못했어요."; drain(); }
    }

    public void dismiss(String id, Runnable changed) {
        Suggestion s = find(id);
        if (s != null && status(s) == Status.PENDING) setStatus(id, Status.DISMISSED, changed);
    }

    /** Ends the invite for everyone. Friends then see that the invite is over. */
    public void close(Runnable changed) {
        if (trip == null) return;
        Trip closing = trip;
        end("");
        service.closeTrip(closing, new Callback<>() {
            @Override public void onSuccess(Void ignored) {}
            @Override public void onFailure(String error) {}
        });
        changed.run();
    }

    private void end(String reason) {
        generation++;
        trip = null; suggestions = List.of(); local.clear(); writes.clear();
        opening = false; polling = false; writing = false; message = reason;
        try { store.clear(); } catch (RuntimeException ignored) {}
    }

    public Status status(Suggestion s) { return local.getOrDefault(s.id(), s.status()); }
    public Suggestion find(String id) {
        for (Suggestion s : suggestions) if (s.id().equals(id)) return s;
        return null;
    }
    /** Waiting for the driver, oldest first. */
    public List<Suggestion> pending() { return suggestions.stream().filter(s -> status(s) == Status.PENDING).toList(); }
    /** Already decided, newest first, a few only. */
    public List<Suggestion> handled() {
        List<Suggestion> out = new ArrayList<>(suggestions.stream().filter(s -> status(s) != Status.PENDING).toList());
        java.util.Collections.reverse(out);
        return List.copyOf(out.subList(0, Math.min(HANDLED_SHOWN, out.size())));
    }
    public Trip trip() { return trip; }
    public boolean isOpen(long now) { return trip != null && trip.open(now); }
    public boolean opening() { return opening; }
    public String message() { return message; }
    public String inviteUrl() { return trip == null ? "" : service.inviteUrl(trip); }
}
