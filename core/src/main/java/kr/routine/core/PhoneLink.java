package kr.routine.core;

import java.util.Objects;
import java.util.regex.Pattern;
import kr.routine.core.Model.Destination;
import kr.routine.core.NaviGateway.Callback;

/**
 * 폰에서 경로 보내기: the driver's phone sends a destination, the car shows it as today's route. The car links a
 * phone once (QR), then takes at most one waiting place at a time. A phone place is not a stock POI: it travels as
 * coordinates and a name, like a friend's stop.
 */
public final class PhoneLink {
    private PhoneLink() {}

    public static final String PREFIX = "phone:";
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{48}");

    /** The car's side of a link: its id and the car's secret. */
    public record Link(String id, String carToken) {
        public Link {
            if (id == null || !UUID.matcher(id).matches()) throw new IllegalArgumentException("link id");
            if (carToken == null || !TOKEN.matcher(carToken).matches()) throw new IllegalArgumentException("car token");
        }
    }

    /** A new link and the phone's secret, which only the QR carries. */
    public record Pairing(Link link, String phoneToken) {
        public Pairing {
            Objects.requireNonNull(link);
            if (phoneToken == null || !TOKEN.matcher(phoneToken).matches()) throw new IllegalArgumentException("phone token");
        }
    }

    /** A place the phone sent. */
    public record Route(String id, String name, String address, double latitude, double longitude, long sentAt) {
        public Route {
            if (id == null || id.isBlank() || name == null || name.isBlank()) throw new IllegalArgumentException("route");
            address = address == null ? "" : address;
        }
        public Destination toDestination() { return new Destination(PREFIX + id, name, address, latitude, longitude, ""); }
    }

    public static boolean isPhoneDestination(Destination d) { return d != null && d.id().startsWith(PREFIX); }
    public static String routeId(Destination d) { return isPhoneDestination(d) ? d.id().substring(PREFIX.length()) : null; }

    public interface Service {
        void createLink(Callback<Pairing> callback);
        /** The waiting place, taken once; null when nothing waits. */
        void take(Link link, Callback<Route> callback);
        void revoke(Link link, Callback<Void> callback);
        /** Address the phone opens from the QR; empty when no phone page is configured. */
        String pairUrl(Pairing pairing);
    }

    public interface Store {
        Link load();
        void save(Link link);
        void clear();
    }

    public static final class MemoryStore implements Store {
        private Link link;
        @Override public Link load() { return link; }
        @Override public void save(Link value) { link = value; }
        @Override public void clear() { link = null; }
    }
}
