package kr.routine.core;

import java.util.Objects;
import java.util.function.Consumer;
import kr.routine.core.NaviGateway.Callback;
import kr.routine.core.PhoneLink.*;

/**
 * Car side of 폰에서 경로 보내기. UI thread only. Links one phone, then checks for a sent place when the app is on
 * screen: at once when it comes to the front, then once a minute. It never touches the navi; VehicleSession decides
 * what to do with a place.
 */
public final class PhoneInbox {
    public static final long POLL_MS = 60_000;

    private final Service service;
    private final Store store;
    private Link link;
    /** Shown on the pairing screen only; never stored. */
    private Pairing pairing;
    private boolean pairingBusy, polling;
    private long lastPoll = Long.MIN_VALUE / 2, generation;
    private String message = "";

    public PhoneInbox(Service service, Store store) {
        this.service = Objects.requireNonNull(service);
        this.store = Objects.requireNonNull(store);
        try { link = store.load(); } catch (RuntimeException unreadable) { store.clear(); }
    }

    /** Creates a link and keeps the phone's secret in memory for the QR. No-op while one exists or is being made. */
    public void pair(Runnable changed) {
        if (pairingBusy || link != null) return;
        pairingBusy = true; message = "연결 QR을 만들고 있어요";
        long gen = ++generation;
        changed.run();
        service.createLink(new Callback<>() {
            @Override public void onSuccess(Pairing value) {
                if (gen != generation) return;
                pairingBusy = false;
                if (value == null) { message = "연결 QR을 만들지 못했어요."; changed.run(); return; }
                pairing = value; link = value.link(); message = "";
                try { store.save(value.link()); } catch (RuntimeException ignored) { message = "연결을 이 기기에 저장하지 못했어요."; }
                changed.run();
            }
            @Override public void onFailure(String error) {
                if (gen != generation) return;
                pairingBusy = false;
                message = error != null && error.startsWith("TOO_BUSY") ? "지금은 연결할 수 없어요. 잠시 뒤 다시 시도해 주세요."
                    : "연결 QR을 만들지 못했어요. 인터넷 연결을 확인해 주세요.";
                changed.run();
            }
        });
    }

    /** The QR is shown once, while pairing; leaving the screen hides the phone's secret for good. */
    public void finishPairing(Runnable changed) { if (pairing != null) { pairing = null; changed.run(); } }

    /** Forgets the phone; it can no longer send to this car. */
    public void unpair(Runnable changed) {
        Link old = link;
        generation++; link = null; pairing = null; pairingBusy = false; polling = false; message = "";
        try { store.clear(); } catch (RuntimeException ignored) {}
        if (old != null) service.revoke(old, new Callback<>() { public void onSuccess(Void v) {} public void onFailure(String e) {} });
        changed.run();
    }

    /** Takes a waiting place at most every POLL_MS unless forced (the app came to the front). */
    public void poll(long now, boolean force, Consumer<Route> received, Runnable changed) {
        if (link == null || polling) return;
        if (!force && now - lastPoll < POLL_MS) return;
        polling = true; lastPoll = now;
        long gen = generation;
        service.take(link, new Callback<>() {
            @Override public void onSuccess(Route value) {
                if (gen != generation) return;
                polling = false;
                boolean hadMessage = !message.isEmpty();
                message = "";
                if (value != null) received.accept(value); else if (hadMessage) changed.run();
            }
            @Override public void onFailure(String error) {
                if (gen != generation) return;
                polling = false;
                if (error != null && error.startsWith("NOT_ALLOWED")) {
                    link = null; pairing = null; message = "폰 연결이 끊겼어요. 다시 연결해 주세요.";
                    try { store.clear(); } catch (RuntimeException ignored) {}
                    changed.run();
                }
                // Other failures stay quiet: the next minute tries again and the driver can still pick a place.
            }
        });
    }

    public boolean linked() { return link != null; }
    public boolean pairing() { return pairingBusy; }
    public String pairUrl() { return pairing == null ? "" : service.pairUrl(pairing); }
    public String message() { return message; }
}
