package kr.routine.phone;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure parsing of 늘길's own links. No Android types, so checks run on a plain JVM.
 * - Invite link from a friend's car: https://nulgil-invite.vercel.app/?c=ABC234 (also inside shared text).
 * - The car's phone link (QR): https://nulgil-invite.vercel.app/phone.html?l=<uuid>&k=<48 hex>.
 */
public final class Links {
    private Links() {}

    /** A car this phone can send its own destination to. */
    public record CarLink(String id, String token) {}

    private static final Pattern CODE = Pattern.compile("[A-Z2-9]{6}");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{48}");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+");

    /** Invite code from an invite link, from text containing one, or a bare 6-letter code; null otherwise. */
    public static String inviteCode(String text, String host) {
        if (text == null) return null;
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String code = param(m.group(), host, null, "c");
            if (code != null) {
                code = code.trim().toUpperCase();
                if (CODE.matcher(code).matches()) return code;
            }
        }
        String bare = text.trim().replace(" ", "").toUpperCase();
        return CODE.matcher(bare).matches() ? bare : null;
    }

    /** The car's phone link from the QR address; null when the text is not one. */
    public static CarLink carLink(String text, String host) {
        if (text == null) return null;
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String id = param(m.group(), host, "/phone.html", "l"), token = param(m.group(), host, "/phone.html", "k");
            if (id != null && token != null && UUID.matcher(id).matches() && TOKEN.matcher(token).matches()) return new CarLink(id, token);
        }
        return null;
    }

    /** True when the text looks like something shared from a map (has a link or more than a code). */
    public static boolean looksLikePlace(String text, String host) {
        if (text == null || text.isBlank()) return false;
        return inviteCode(text, host) == null && carLink(text, host) == null;
    }

    private static String param(String url, String host, String path, String name) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || !uri.getHost().equalsIgnoreCase(host)) return null;
            if (path != null && !path.equals(uri.getPath())) return null;
            String query = uri.getRawQuery();
            if (query == null) return null;
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            }
        } catch (Exception malformed) { return null; }
        return null;
    }
}
