import kr.routine.phone.Links;

/** 늘길's own links in shared text: invite codes and the car's phone link. Plain JVM, no Android. */
public final class LinksCheck {
    private static final String HOST = "nulgil-invite.vercel.app";
    private static int passed;

    public static void main(String[] args) {
        expect("ABC234".equals(Links.inviteCode("https://nulgil-invite.vercel.app/?c=ABC234", HOST)), "invite link gives its code");
        expect("ABC234".equals(Links.inviteCode("가는 길에 들러 줘 https://nulgil-invite.vercel.app/?c=abc234&share=1", HOST)),
            "a code inside shared text is found and upper-cased");
        expect("ABC234".equals(Links.inviteCode(" abc 234 ", HOST)), "a typed code with spaces is accepted");
        expect(Links.inviteCode("https://evil.example/?c=ABC234", HOST) == null, "codes from other sites are ignored");
        expect(Links.inviteCode("ABC10I", HOST) == null, "ambiguous letters (0, 1, I) are not codes");
        String pair = "https://nulgil-invite.vercel.app/phone.html?l=36f5e553-59dd-4ebb-9665-9a217c0f8b2a&k=" + "b".repeat(48);
        Links.CarLink car = Links.carLink(pair, HOST);
        expect(car != null && car.id().startsWith("36f5e553") && car.token().length() == 48, "the car's QR link gives id and token");
        expect(Links.carLink(pair.replace("phone.html", "index.html"), HOST) == null, "only the phone page links a car");
        String naver = "[네이버지도]\n홈플러스 문경점\n경북 문경시 모전로 65 홈플러스\nhttps://naver.me/xAfHKpx5";
        expect(Links.looksLikePlace(naver, HOST) && Links.inviteCode(naver, HOST) == null, "a Naver Map share is a place, not a code");
        expect(!Links.looksLikePlace(pair, HOST), "the car link is not treated as a place");
        System.out.println("LinksCheck: " + passed + " assertions passed");
    }

    private static void expect(boolean condition, String label) {
        if (!condition) { System.out.println("FAIL " + label); System.exit(1); }
        passed++; System.out.println("PASS " + label);
    }
}
