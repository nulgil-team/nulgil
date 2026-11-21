package kr.routine.app.vehicle;

import java.util.List;
import kr.routine.core.Model.Destination;

/**
 * What the invite panel shows (친구 들를 곳). Plain data so the panel's look can change without touching the session.
 * addMode: NOW = add to the route being guided, PLAN = plan with the destination before departure, NONE = not now
 * (addHint says why).
 */
public record InviteUiState(boolean available, boolean open, boolean opening, String code, String url, long expiresAt,
                            List<Item> pending, List<Item> handled, String message, boolean canOpen,
                            String addMode, String addHint) {
    /** status: PENDING, PLANNED (waiting in the planned stops), REQUESTED, ADDED, UNCONFIRMED, FAILED, DISMISSED. */
    public record Item(String id, String sender, String placeName, String address, String note, String status,
                       Destination stop) {}

    public InviteUiState {
        pending = List.copyOf(pending); handled = List.copyOf(handled);
        code = code == null ? "" : code; url = url == null ? "" : url; message = message == null ? "" : message;
        addHint = addHint == null ? "" : addHint;
    }

    public static InviteUiState unavailable() {
        return new InviteUiState(false, false, false, "", "", 0, List.of(), List.of(), "", false, "NONE", "");
    }

    /** Places still waiting for the driver (not yet planned or sent). */
    public int waiting() { return (int) pending.stream().filter(item -> item.status().equals("PENDING")).count(); }
}
