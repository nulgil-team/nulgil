package kr.routine.bridge;

import kr.routine.presentation.DemoBackend;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Real-socket checks. A fresh state file and ephemeral port are used on every run. */
public final class BridgeChecks {
    public record JsonFixture(String text, List<Object> values, Map<String, Object> nested) {}
    private static int checks;
    private static final String ORIGIN = "http://localhost:5173";
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public static void main(String[] args) throws Exception {
        Path root = args.length == 2 && args[0].equals("--evidence-dir") ? Path.of(args[1]) : Path.of("bridge", "check-runs");
        Files.createDirectories(root);
        Path run = Files.createTempDirectory(root, "http-check-");
        jsonChecks();
        require(BridgeServer.isAllowedHost("127.0.0.1", 80), "HTTP default port implicit IP Host");
        require(BridgeServer.isAllowedHost("localhost", 80), "HTTP default port implicit localhost Host");
        require(BridgeServer.isAllowedHost("127.0.0.1:80", 80), "HTTP default port explicit Host");
        require(!BridgeServer.isAllowedHost("127.0.0.1", 8791), "nondefault port requires actual port");
        require(!BridgeServer.isAllowedHost("localhost.evil", 80), "default port still rejects foreign Host");
        require(!BridgeServer.isAllowedHost("127.0.0.1:8791", 80), "wrong explicit port rejected");
        for (String origin : List.of("https://example.com", "null", "http://localhost:5173/", "http://localhost.evil:5173", "http://user@localhost:5173", "file://localhost", "http://localhost:80", "https://localhost:443")) {
            rejects(() -> BridgeServer.validateOriginSetting(origin), "reject unsafe configured origin");
        }
        try (BridgeServer bridge = new BridgeServer(new DemoBackend(run.resolve("state.properties"), ZoneId.of("Asia/Seoul")), 0, BridgeServer.DEFAULT_ORIGINS)) {
            require(bridge.baseUrl().startsWith("http://127.0.0.1:"), "IPv4 loopback URL");
            require(java.util.Base64.getUrlDecoder().decode(bridge.token()).length == 32, "32-byte token");
            HttpResponse<String> initial = request(bridge, "GET", "/v1/state", null, ORIGIN, bridge.token(), null);
            status(initial, 200, "initial state");
            has(initial, "\"mode\":\"DEMO\"", "mode explicit");
            has(initial, "\"collectionEnabled\":false", "consent default off");
            has(initial, "\"liveNavigation\":false", "live navigation capability false");
            has(initial, "\"mapGeometry\":false", "map geometry capability false");
            has(initial, "\"etaPerPreference\":false", "preference ETA capability false");
            has(initial, "\"estimateMinutes\":null", "unrequested traffic nullable");
            header(initial, "Access-Control-Allow-Origin", ORIGIN);
            header(initial, "Cache-Control", "no-store"); header(initial, "X-Content-Type-Options", "nosniff");
            header(initial, "Vary", "Origin");
            require(!initial.body().contains(bridge.token()), "state never discloses token");
            status(request(bridge, "GET", "/v1/state", null, null, bridge.token(), null), 200, "native client without origin");
            status(request(bridge, "GET", "/v1/state", null, "http://127.0.0.1:5173", bridge.token(), null), 200, "second default origin");
            error(request(bridge, "GET", "/v1/state", null, ORIGIN, null, null), 401, "UNAUTHORIZED");
            error(request(bridge, "POST", "/v1/commands", "operation=recommend", ORIGIN, null, "application/x-www-form-urlencoded"), 401, "UNAUTHORIZED");
            error(request(bridge, "GET", "/v1/state", null, ORIGIN, "wrong", null), 401, "UNAUTHORIZED");
            HttpResponse<String> forbidden = request(bridge, "GET", "/v1/state", null, "http://evil.example", bridge.token(), null);
            error(forbidden, 403, "ORIGIN_NOT_ALLOWED");
            require(forbidden.headers().firstValue("Access-Control-Allow-Origin").isEmpty(), "forbidden origin has no CORS grant");
            error(request(bridge, "GET", "/v1/state", null, "null", bridge.token(), null), 403, "ORIGIN_NOT_ALLOWED");
            error(request(bridge, "GET", "/connection.json", null, ORIGIN, bridge.token(), null), 404, "NOT_FOUND");
            error(request(bridge, "GET", "/v1/state?token=hidden", null, ORIGIN, bridge.token(), null), 400, "QUERY_NOT_ALLOWED");
            error(request(bridge, "GET", "/v1/state/extra", null, ORIGIN, bridge.token(), null), 404, "NOT_FOUND");
            error(request(bridge, "POST", "/v1/state", "", ORIGIN, bridge.token(), "application/x-www-form-urlencoded"), 405, "METHOD_NOT_ALLOWED");
            error(request(bridge, "GET", "/v1/commands", null, ORIGIN, bridge.token(), null), 405, "METHOD_NOT_ALLOWED");
            error(request(bridge, "POST", "/v1/commands", "{}", ORIGIN, bridge.token(), "application/json"), 415, "UNSUPPORTED_MEDIA_TYPE");
            error(request(bridge, "POST", "/v1/commands", "operation=recommend", ORIGIN, bridge.token(), null), 415, "UNSUPPORTED_MEDIA_TYPE");
            error(request(bridge, "POST", "/v1/commands", "operation=recommend", ORIGIN, bridge.token(), "application/x-www-form-urlencoded;charset=latin1"), 415, "UNSUPPORTED_CHARSET");
            HttpResponse<String> preflight = CLIENT.send(HttpRequest.newBuilder(URI.create(bridge.baseUrl()+"/v1/commands"))
                .timeout(Duration.ofSeconds(5)).header("Origin", ORIGIN).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-routine-token").method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            status(preflight, 204, "token-free preflight"); header(preflight, "Access-Control-Allow-Headers", "Content-Type, X-Routine-Token");
            require(preflight.body().isEmpty(), "preflight body empty");
            error(preflight(bridge, ORIGIN, "POST", "x-unexpected"), 400, "HEADER_NOT_ALLOWED");
            error(preflight(bridge, ORIGIN, "DELETE", "x-routine-token"), 405, "METHOD_NOT_ALLOWED");
            error(preflight(bridge, null, "POST", "x-routine-token"), 400, "ORIGIN_REQUIRED");
            require(raw(bridge, "GET", "/v1/state", "localhost:"+bridge.port(), "X-Routine-Token: "+bridge.token()+"\r\n", null).startsWith("HTTP/1.1 200"), "literal localhost Host allowed");
            rawError(bridge, "evil.example:"+bridge.port(), "", null, 403, "HOST_NOT_ALLOWED");
            rawError(bridge, "127.0.0.1:1", "", null, 403, "HOST_NOT_ALLOWED");
            String duplicateToken = raw(bridge, "POST", "/v1/commands", "127.0.0.1:"+bridge.port(),
                "X-Routine-Token: " + bridge.token() + "\r\nX-Routine-Token: " + bridge.token() + "\r\n", bytes("operation=recommend"));
            require(duplicateToken.startsWith("HTTP/1.1 400"), "duplicate security header rejected");
            error(form(bridge, "operation=recommend&operation=confirm"), 400, "DUPLICATE_FIELD");
            error(form(bridge, "operation=recommend&%6fperation=confirm"), 400, "DUPLICATE_FIELD");
            error(form(bridge, "operation=%"), 400, "MALFORMED_FORM");
            error(form(bridge, "operation=%GG"), 400, "MALFORMED_FORM");
            error(form(bridge, "operation=%C3%28"), 400, "INVALID_UTF8");
            error(form(bridge, "operation=%ED%A0%80"), 400, "INVALID_UTF8");
            error(form(bridge, "operation=recommend&"), 400, "MALFORMED_FORM");
            error(form(bridge, "operation=recommend&bad"), 400, "MALFORMED_FORM");
            error(form(bridge, ""), 400, "OPERATION_REQUIRED");
            byte[] invalidUtf = new byte[]{'o','p','e','r','a','t','i','o','n','=',(byte)0xc3,0x28};
            rawError(bridge, "127.0.0.1:"+bridge.port(), "X-Routine-Token: " + bridge.token()+"\r\n", invalidUtf, 400, "INVALID_UTF8");
            error(form(bridge, "operation="+"x".repeat(8193)), 413, "BODY_TOO_LARGE");
            String exactLimit = "operation=recommend&extra=";
            error(form(bridge, exactLimit+"x".repeat(8192-exactLimit.length())), 400, "UNKNOWN_ARGUMENT");
            error(form(bridge, "operation=noSuchCommand"), 400, "INVALID_COMMAND");
            error(form(bridge, "operation=recommend&surprise=true"), 400, "UNKNOWN_ARGUMENT");
            error(form(bridge, "operation=setContext&now=1"), 400, "MISSING_ARGUMENT");
            error(command(bridge, "setContext", Map.of("now", "invalid", "zoneId", "Asia/Seoul")), 400, "INVALID_ARGUMENT");
            error(command(bridge, "setContext", Map.of("now", "1", "zoneId", "invalid")), 400, "INVALID_ARGUMENT");
            error(command(bridge, "confirm", Map.of()), 409, "NOT_READY");
            error(command(bridge, "seedDemo", Map.of()), 409, "COLLECTION_DISABLED");

            long midnight = Instant.parse("2026-11-04T15:05:00Z").toEpochMilli();
            HttpResponse<String> midnightState = command(bridge, "setContext", Map.of("now", ""+midnight, "zoneId", "Asia/Seoul"));
            status(midnightState, 200, "KST next day"); has(midnightState, "\"localDate\":\"2026-11-05\",\"day\":4,\"minute\":5", "date reflects selected timezone");
            HttpResponse<String> utc = command(bridge, "setContext", Map.of("now", ""+midnight, "zoneId", "UTC"));
            has(utc, "\"localDate\":\"2026-11-04\",\"day\":3,\"minute\":905", "same instant UTC day");
            status(command(bridge, "setContext", Map.of("now", ""+Instant.parse("2026-11-03T23:00:00Z").toEpochMilli(), "zoneId", "Asia/Seoul")), 200, "fixture time");
            status(command(bridge, "setConsent", Map.of("enabled", "true")), 200, "consent before demo seed");
            status(command(bridge, "seedDemo", Map.of()), 200, "seed uses real core");
            HttpResponse<String> recommended = command(bridge, "recommend", Map.of("historyOnly", "true"));
            status(recommended, 200, "history recommendation"); has(recommended, "\"source\":\"HISTORY\"", "history source");
            has(recommended, "소프트웨어드림센터", "Korean destination preserved"); has(recommended, "\"canConfirm\":true", "ready confirm");
            HttpResponse<String> normal = command(bridge, "refreshTraffic", Map.of());
            has(normal, "\"estimateMinutes\":28", "normal ETA"); has(normal, "\"baselineMinutes\":29", "history baseline");
            has(normal, "\"delayMinutes\":-1", "normal delta");
            status(command(bridge, "setDemoScenario", Map.of("traffic", "CONGESTED")), 200, "switch congestion");
            HttpResponse<String> congested = command(bridge, "refreshTraffic", Map.of());
            has(congested, "\"estimateMinutes\":44", "congested ETA"); has(congested, "\"delayMinutes\":15", "congested delta");
            require(!congested.body().contains(bridge.token()), "fixture excludes token");
            Files.writeString(run.resolve("state-congested.json"), congested.body(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            status(command(bridge, "setPreference", Map.of("value", "COMFORTABLE")), 200, "preference command");
            has(command(bridge, "refreshTraffic", Map.of()), "\"estimateMinutes\":44", "preference does not fabricate ETA");
            command(bridge, "setDemoScenario", Map.of("traffic", "MISSING"));
            HttpResponse<String> missing = command(bridge, "refreshTraffic", Map.of());
            has(missing, "\"status\":\"UNAVAILABLE\"", "missing traffic status");
            has(missing, "\"estimateMinutes\":null", "missing traffic not zero");
            command(bridge, "setDemoScenario", Map.of("parked", "false"));
            error(command(bridge, "confirm", Map.of()), 409, "VEHICLE_NOT_PARKED");
            command(bridge, "setDemoScenario", Map.of("parked", "true"));
            for (String route : List.of("ACTIVE", "UNAVAILABLE")) {
                String reason = route.equals("ACTIVE") ? "ALREADY_GUIDING" : "ROUTE_UNAVAILABLE";
                status(command(bridge, "setDemoScenario", Map.of("activeRoute", route)), 200, "set route scenario " + route);
                HttpResponse<String> observed = command(bridge, "refreshActiveRoute", Map.of());
                has(observed, "\"activeRoute\":\"" + route + "\"", "route classification over HTTP");
                has(observed, "\"state\":\"" + reason + "\"", "route hold over HTTP");
                error(command(bridge, "confirm", Map.of()), 409, reason);
                has(request(bridge, "GET", "/v1/state", null, ORIGIN, bridge.token(), null), "\"navigationRequests\":0", "blocked confirm sends zero requests: " + route);
            }
            has(command(bridge, "setDemoScenario", Map.of("activeRoute", "INACTIVE")), "\"canConfirm\":true", "inactive after explicit route observation");
            HttpResponse<String> confirmed = command(bridge, "confirm", Map.of());
            has(confirmed, "\"state\":\"VERIFIED\"", "demo confirmation");
            has(confirmed, "\"navigationRequests\":1", "one actual gateway request");
            error(command(bridge, "confirm", Map.of()), 409, "TRIP_LOCKED");
            has(request(bridge, "GET", "/v1/state", null, ORIGIN, bridge.token(), null), "\"navigationRequests\":1", "duplicate confirm never reissues");
            command(bridge, "newDemoTrip", Map.of());
            command(bridge, "recommend", Map.of());
            command(bridge, "setDemoScenario", Map.of("failNext", "true"));
            has(command(bridge, "confirm", Map.of()), "\"state\":\"UNCONFIRMED\"", "unknown confirmation explicit");
            error(command(bridge, "confirm", Map.of()), 409, "TRIP_LOCKED");
            Files.writeString(run.resolve("state-unconfirmed.json"), request(bridge, "GET", "/v1/state", null, ORIGIN, bridge.token(), null).body(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            command(bridge, "newDemoTrip", Map.of());
            command(bridge, "recommend", Map.of());
            HttpRequest confirmRequest = HttpRequest.newBuilder(URI.create(bridge.baseUrl()+"/v1/commands"))
                .timeout(Duration.ofSeconds(5)).header("X-Routine-Token", bridge.token()).header("Origin", ORIGIN)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("operation=confirm")).build();
            var first = CLIENT.sendAsync(confirmRequest, HttpResponse.BodyHandlers.ofString());
            var second = CLIENT.sendAsync(confirmRequest, HttpResponse.BodyHandlers.ofString());
            Set<Integer> statuses = Set.of(first.get().statusCode(), second.get().statusCode());
            require(statuses.equals(Set.of(200,409)), "simultaneous confirms have one success and one conflict");
            has(request(bridge, "GET", "/v1/state", null, ORIGIN, bridge.token(), null), "\"navigationRequests\":3", "simultaneous confirm adds only one to cumulative request count");
        }
        try (BridgeServer custom = new BridgeServer(new DemoBackend(run.resolve("custom.properties"), ZoneId.of("Asia/Seoul")), 0, Set.of("http://localhost:4173"))) {
            error(request(custom, "GET", "/v1/state", null, ORIGIN, custom.token(), null), 403, "ORIGIN_NOT_ALLOWED");
            status(request(custom, "GET", "/v1/state", null, "http://localhost:4173", custom.token(), null), 200, "custom exact origin");
        }
        scenarioHistory(run);
        String summary = "BridgeChecks PASS: " + checks + " assertions; real HTTP requests; token-free JSON fixtures.\n";
        Files.writeString(run.resolve("result.txt"), summary, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.print(summary); System.out.println("Evidence: " + run.toAbsolutePath());
    }

    private static void scenarioHistory(Path run) throws Exception {
        try (BridgeServer bridge = new BridgeServer(new DemoBackend(run.resolve("scenario-history.properties"), ZoneId.of("Asia/Seoul")), 0, Set.of(ORIGIN))) {
            status(command(bridge, "setConsent", Map.of("enabled", "true")), 200, "scenario collection consent");
            status(command(bridge, "seedDemo", Map.of("days", "2")), 200, "two day seed via HTTP");
            HttpResponse<String> shortage = command(bridge, "recommend", Map.of("historyOnly", "true"));
            has(shortage, "\"holdType\":\"NOT_ENOUGH\"", "shortage enum serialized");
            has(shortage, "\"samples\":2", "shortage samples serialized");
            status(command(bridge, "clearHistory", Map.of()), 200, "clear scenario history");
            String office = kr.routine.core.DemoFixtures.OFFICE.id(), other = kr.routine.core.DemoFixtures.OTHER.id();
            status(command(bridge, "seedDemo", Map.of("days", "3", "destinationIds", office + "," + other)), 200, "two candidate seed via form");
            HttpResponse<String> tie = command(bridge, "recommend", Map.of("historyOnly", "true"));
            has(tie, "\"holdType\":\"TIE\"", "tie enum serialized");
            has(tie, "\"candidates\":[{\"destination\":{\"id\":", "candidate array contains destinations");
            has(tie, "\"id\":\"" + office + "\"", "first destination in tie JSON");
            has(tie, "\"id\":\"" + other + "\"", "second destination in tie JSON");
            HttpResponse<String> off = command(bridge, "setConsent", Map.of("enabled", "false"));
            has(off, "\"candidates\":[]", "opt-out immediately empties HTTP candidate list");
            has(off, "\"history\":[]", "opt-out empties HTTP comparison list");
            status(command(bridge, "setConsent", Map.of("enabled", "true")), 200, "re-enable scenario collection");
            status(command(bridge, "clearHistory", Map.of()), 200, "clear prior tie fixture");
            status(command(bridge, "seedDemo", Map.of("days", "3", "estimateDays", "2")), 200, "comparison shortage seed");
            status(command(bridge, "recommend", Map.of("historyOnly", "true")), 200, "one learned destination");
            HttpResponse<String> noCompare = command(bridge, "refreshTraffic", Map.of());
            has(noCompare, "\"kind\":\"NO_COMPARE\"", "comparison shortage enum");
            has(noCompare, "\"history\":[{\"localDate\":\"2026-10-28\",\"minutes\":28},{\"localDate\":\"2026-10-21\",\"minutes\":29}]", "eligible comparison dates serialized as strings");
            has(noCompare, "\"baselineMinutes\":null", "insufficient comparison has no invented median");
            status(command(bridge, "seedDemo", Map.of("days", "3")), 200, "add third comparison date");
            HttpResponse<String> calm = command(bridge, "refreshTraffic", Map.of());
            has(calm, "\"kind\":\"CALM\"", "calm enum serialized");
            has(calm, "\"baselineMinutes\":29", "comparison median serialized");
            HttpResponse<String> optOut = command(bridge, "setConsent", Map.of("enabled", "false"));
            has(optOut, "\"history\":[]", "populated comparison hidden on opt-out response");
            has(optOut, "\"baselineMinutes\":null", "opt-out hides cached median");
            has(request(bridge, "GET", "/v1/state", null, ORIGIN, bridge.token(), null), "\"history\":[]", "later GET stays private");
            status(command(bridge, "setConsent", Map.of("enabled", "true")), 200, "restore collection for traffic scenarios");
            status(command(bridge, "selectDestination", Map.of("id", office, "subId", "0")), 200, "explicit selection after opt-out");
            for (String scenario : List.of("STALE", "UNKNOWN_UNITS")) {
                status(command(bridge, "setDemoScenario", Map.of("traffic", scenario)), 200, "select traffic scenario " + scenario);
                HttpResponse<String> traffic = command(bridge, "refreshTraffic", Map.of());
                has(traffic, "\"kind\":\"" + scenario + "\"", "distinct traffic enum " + scenario);
                has(traffic, "\"estimateMinutes\":null", "invalid estimate duration null " + scenario);
                has(traffic, "\"history\":[]", "invalid estimate has no comparison " + scenario);
            }
            error(command(bridge, "seedDemo", Map.of("days", "3", "estimateDays", "7")), 400, "INVALID_ARGUMENT");
            error(command(bridge, "seedDemo", Map.of("destinationIds", office + ",missing")), 400, "DESTINATION_NOT_FOUND");
            HttpResponse<String> last = request(bridge, "GET", "/v1/state", null, ORIGIN, bridge.token(), null);
            has(last, "\"navigationRequests\":0", "all state scenarios require no navigation dispatch");
            has(last, "\"selections\":3,\"estimates\":3", "failed seed validates all inputs before writes");
        }
    }

    private static HttpResponse<String> preflight(BridgeServer bridge, String origin, String method, String headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(bridge.baseUrl()+"/v1/commands")).timeout(Duration.ofSeconds(5));
        if (origin != null) request.header("Origin", origin);
        return CLIENT.send(request.header("Access-Control-Request-Method", method).header("Access-Control-Request-Headers", headers)
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void jsonChecks() {
        Map<String, Object> nested = new LinkedHashMap<>(); nested.put("nothing", null); nested.put("enabled", true);
        String json = JsonWriter.write(new JsonFixture("한글\"\\\n\r\t\b\f\u0000\ud800\ud83d\ude97", List.of(1, 2.5, false), nested));
        require(json.equals("{\"text\":\"한글\\\"\\\\\\n\\r\\t\\b\\f\\u0000\\ud800\\ud83d\\ude97\",\"values\":[1,2.5,false],\"nested\":{\"nothing\":null,\"enabled\":true}}"), "strict JSON escaping/record/list/map/null");
        rejects(() -> JsonWriter.write(Double.NaN), "reject NaN JSON");
        rejects(() -> JsonWriter.write(Double.POSITIVE_INFINITY), "reject infinity JSON");
        rejects(() -> JsonWriter.write(Map.of(1, "bad")), "reject non-string keys");
        rejects(() -> JsonWriter.write(new Object()), "reject unsupported value");
        Map<String, String> decoded = BridgeServer.parseForm(bytes("operation=recommend&value=" + enc("한글 + & = 🚗")));
        require(decoded.get("value").equals("한글 + & = 🚗"), "strict UTF-8 form roundtrip");
    }
    private static HttpResponse<String> command(BridgeServer bridge, String op, Map<String,String> args) throws Exception {
        StringBuilder form = new StringBuilder("operation=").append(enc(op));
        args.forEach((key,value) -> form.append('&').append(enc(key)).append('=').append(enc(value)));
        return form(bridge, form.toString());
    }
    private static HttpResponse<String> form(BridgeServer bridge, String body) throws Exception {
        return request(bridge, "POST", "/v1/commands", body, ORIGIN, bridge.token(), "application/x-www-form-urlencoded;charset=UTF-8");
    }
    private static HttpResponse<String> request(BridgeServer bridge, String method, String path, String body, String origin, String token, String type) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(bridge.baseUrl()+path)).timeout(Duration.ofSeconds(5));
        if (origin != null) request.header("Origin", origin);
        if (token != null) request.header("X-Routine-Token", token);
        if (type != null) request.header("Content-Type", type);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private static String raw(BridgeServer bridge, String method, String path, String host, String extraHeaders, byte[] body) throws Exception {
        byte[] payload = body == null ? new byte[0] : body;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", bridge.port()), 5000); socket.setSoTimeout(5000);
            socket.getOutputStream().write(bytes(method+" "+path+" HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: "+payload.length+"\r\n"+extraHeaders+"\r\n"));
            socket.getOutputStream().write(payload); socket.getOutputStream().flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream(); socket.getInputStream().transferTo(response);
            return response.toString(StandardCharsets.UTF_8);
        }
    }
    private static void rawError(BridgeServer bridge, String host, String extra, byte[] body, int status, String code) throws Exception {
        String response = raw(bridge, "POST", "/v1/commands", host, extra, body);
        require(response.startsWith("HTTP/1.1 "+status), "raw HTTP " + status); require(response.contains("\"code\":\""+code+"\""), "raw code " + code);
    }
    private static void status(HttpResponse<String> response, int status, String label) {
        require(response.statusCode() == status, label + " expected " + status + " received " + response.statusCode()+" "+response.body());
    }
    private static void error(HttpResponse<String> response, int status, String code) {
        status(response, status, code); has(response, "\"code\":\""+code+"\"", "error code "+code); has(response, "\"schemaVersion\":\"1.0\"", "error schema");
    }
    private static void has(HttpResponse<String> response, String fragment, String label) { require(response.body().contains(fragment), label+": "+response.body()); }
    private static void header(HttpResponse<String> response, String key, String expected) { require(response.headers().firstValue(key).orElse("").equals(expected), key); }
    private static void require(boolean condition, String label) { if (!condition) throw new AssertionError(label); checks++; }
    private static void rejects(Runnable operation, String label) { try { operation.run(); } catch (IllegalArgumentException expected) { checks++; return; } throw new AssertionError(label); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
