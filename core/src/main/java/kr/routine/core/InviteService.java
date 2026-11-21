package kr.routine.core;

import java.util.List;
import kr.routine.core.Invite.*;
import kr.routine.core.NaviGateway.Callback;

/**
 * Transport for friend suggestions. Callbacks arrive on the UI thread, at most one per call.
 * Error messages from the server keep its code (for example TRIP_CLOSED, NOT_ALLOWED) at the start.
 */
public interface InviteService {
    void createTrip(Callback<Trip> callback);
    void listSuggestions(Trip trip, Callback<List<Suggestion>> callback);
    void setStatus(Trip trip, String suggestionId, Status status, Callback<Void> callback);
    void closeTrip(Trip trip, Callback<Void> callback);
    /** Web page a friend opens; empty when no page is configured (the code still works there). */
    String inviteUrl(Trip trip);

    /** Keeps the open invite across app restarts. */
    interface TripStore {
        Trip load();
        void save(Trip trip);
        void clear();
    }

    final class MemoryTripStore implements TripStore {
        private Trip trip;
        @Override public Trip load() { return trip; }
        @Override public void save(Trip value) { trip = value; }
        @Override public void clear() { trip = null; }
    }
}
