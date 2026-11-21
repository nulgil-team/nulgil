package kr.routine.core;

import java.util.Objects;
import kr.routine.core.Model.Destination;
import kr.routine.core.Model.StopState;

/**
 * Friend stop suggestions (친구 들를 곳). A friend opens the invite page with the trip code and sends a place;
 * the driver decides in the car. The server only stores the code, the suggested places and their status:
 * never the car's location, destination or route.
 */
public final class Invite {
    private Invite() {}

    /** Same values as the server's invite_suggestions.status check. */
    public enum Status { PENDING, REQUESTED, ADDED, UNCONFIRMED, FAILED, DISMISSED }

    /** Prefix of Destination.id for a friend's place. Such a place has no stock POI id. */
    public static final String STOP_PREFIX = "invite:";

    /** One open invite. The car token proves ownership to the server and never leaves the car. */
    public record Trip(String code, String carToken, long expiresAt) {
        public Trip {
            Objects.requireNonNull(code); Objects.requireNonNull(carToken);
            if (!code.matches("[A-Z2-9]{6}") || carToken.isBlank()) throw new IllegalArgumentException("잘못된 초대 정보");
        }
        public boolean open(long now) { return now < expiresAt; }
    }

    public record Suggestion(String id, String sender, String placeName, String address, double latitude,
                             double longitude, String note, Status status, long createdAt) {
        public Suggestion {
            Objects.requireNonNull(id); Objects.requireNonNull(sender); Objects.requireNonNull(placeName);
            address = address == null ? "" : address; note = note == null ? "" : note;
            Objects.requireNonNull(status);
            if (id.isBlank() || placeName.isBlank() || !Double.isFinite(latitude) || !Double.isFinite(longitude))
                throw new IllegalArgumentException("잘못된 제안");
        }
        /** The place as a stop. Its id marks it as external so the navi adapter sends coordinates, not a POI. */
        public Destination toStop() {
            return new Destination(STOP_PREFIX + id, placeName, address.isBlank() ? sender + " 님이 보낸 곳" : address,
                latitude, longitude, "");
        }
    }

    public static boolean isInviteStop(Destination value) { return value != null && value.id().startsWith(STOP_PREFIX); }

    /** Suggestion id of a stop made by Suggestion.toStop(), or null for a stock place. */
    public static String suggestionId(Destination value) {
        return isInviteStop(value) ? value.id().substring(STOP_PREFIX.length()) : null;
    }

    /** Status to report after a stop request settled; null while it is still in flight. */
    public static Status after(StopState state) {
        return switch (state) {
            case ADDED -> Status.ADDED;
            case UNCONFIRMED -> Status.UNCONFIRMED;
            case NOT_SENT -> Status.PENDING; // nothing reached the navi: the friend keeps waiting
            default -> null;
        };
    }
}
