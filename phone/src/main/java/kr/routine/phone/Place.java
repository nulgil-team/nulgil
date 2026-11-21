package kr.routine.phone;

/** A place to send, as resolve-place returns it. */
public record Place(String name, String address, double latitude, double longitude, String source) {
    public Place {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("place name");
        if (latitude < 33 || latitude > 39 || longitude < 124 || longitude > 132) throw new IllegalArgumentException("place outside Korea");
        address = address == null ? "" : address;
        source = source == null ? "TEXT" : source;
    }

    public String sourceLabel() {
        return switch (source) {
            case "NAVER" -> "네이버지도에서 공유";
            case "KAKAO" -> "카카오맵에서 공유";
            case "GOOGLE" -> "구글 지도에서 공유";
            default -> "공유한 글";
        };
    }
}
