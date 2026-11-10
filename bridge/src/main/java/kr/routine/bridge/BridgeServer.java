package kr.routine.bridge;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.routine.presentation.BackendException;
import kr.routine.presentation.DemoBackend;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loopback-only transport for the DEMO backend. It never serves files or invokes live navigation. */
public final class BridgeServer implements AutoCloseable {
    public static final String SCHEMA_VERSION = "1.0";
    public static final Set<String> DEFAULT_ORIGINS = Set.of("http://localhost:5173", "http://127.0.0.1:5173");
    public static final int MAX_BODY_BYTES = 8192;
    private final DemoBackend backend;
    private final Set<String> origins;
    private final HttpServer server;
    private final ExecutorService executor;
    private final String token;
    private final AtomicBoolean closed = new AtomicBoolean();

    public BridgeServer(DemoBackend backend, int port, Set<String> origins) throws IOException {
        this.backend = Objects.requireNonNull(backend);
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Port must be 0..65535");
        this.origins = Set.copyOf(Objects.requireNonNull(origins));
        for (String origin : this.origins) validateOriginSetting(origin);
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
        executor = Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "routine-local-http"); thread.setDaemon(true); return thread;
        });
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }
    public String baseUrl() { return "http://127.0.0.1:" + port(); }
    /** Only persist in a local connection file; never include in logs or sample API responses. */
    public String token() { return token; }

    public static void validateOriginSetting(String origin) {
        try {
            URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || !("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !"".equals(uri.getRawPath()) || uri.getPort() < -1 || uri.getPort() > 65535
                    || uri.getPort() == 0 || "http".equals(uri.getScheme()) && uri.getPort() == 80
                    || "https".equals(uri.getScheme()) && uri.getPort() == 443
                    || !origin.equals(uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() == -1 ? "" : ":" + uri.getPort())))
                throw new IllegalArgumentException("Origin must be an exact HTTP(S) localhost/127.0.0.1 origin; omit default ports 80/443");
        } catch (NullPointerException failure) {
            throw new IllegalArgumentException("Origin is required", failure);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        Headers response = exchange.getResponseHeaders();
        response.set("Cache-Control", "no-store");
        response.set("X-Content-Type-Options", "nosniff");
        response.set("Vary", "Origin");
        try {
            String host = oneHeader(exchange, "Host", true);
            if (!isAllowedHost(host, port()))
                throw problem(403, "HOST_NOT_ALLOWED", "로컬 주소로만 접속할 수 있습니다.");
            String origin = oneHeader(exchange, "Origin", false);
            if (origin != null) {
                if (!origins.contains(origin)) throw problem(403, "ORIGIN_NOT_ALLOWED", "허용되지 않은 화면 주소입니다.");
                response.set("Access-Control-Allow-Origin", origin);
            }
            String path = exchange.getRequestURI().getRawPath();
            if (!Set.of("/v1/state", "/v1/commands").contains(path))
                throw problem(404, "NOT_FOUND", "API 경로를 찾을 수 없습니다.");
            if (exchange.getRequestURI().getRawQuery() != null)
                throw problem(400, "QUERY_NOT_ALLOWED", "이 API는 쿼리 문자열을 받지 않습니다.");
            String method = exchange.getRequestMethod();
            String allowedMethod = path.equals("/v1/state") ? "GET" : "POST";
            if (method.equals("OPTIONS")) {
                if (origin == null) throw problem(400, "ORIGIN_REQUIRED", "사전 요청에는 Origin이 필요합니다.");
                String requestedMethod = oneHeader(exchange, "Access-Control-Request-Method", true);
                if (!requestedMethod.equals(allowedMethod))
                    throw problem(405, "METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다.");
                String headers = oneHeader(exchange, "Access-Control-Request-Headers", false);
                if (headers != null) for (String header : headers.split(",", -1)) {
                    if (!Set.of("content-type", "x-routine-token").contains(header.trim().toLowerCase(Locale.ROOT)))
                        throw problem(400, "HEADER_NOT_ALLOWED", "허용되지 않은 사전 요청 헤더입니다.");
                }
                response.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                response.set("Access-Control-Allow-Headers", "Content-Type, X-Routine-Token");
                exchange.sendResponseHeaders(204, -1); return;
            }
            if (!method.equals(allowedMethod)) {
                response.set("Allow", allowedMethod + ", OPTIONS");
                throw problem(405, "METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다.");
            }
            String suppliedToken = oneHeader(exchange, "X-Routine-Token", false);
            if (suppliedToken == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),
                    suppliedToken.getBytes(StandardCharsets.UTF_8)))
                throw problem(401, "UNAUTHORIZED", "로컬 연결 토큰이 필요합니다.");
            if (method.equals("GET")) {
                synchronized (backend) { send(exchange, 200, backend.snapshot()); }
            } else {
                requireFormContentType(oneHeader(exchange, "Content-Type", false));
                byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                if (body.length > MAX_BODY_BYTES) throw problem(413, "BODY_TOO_LARGE", "요청 본문은 8 KiB 이하여야 합니다.");
                Map<String, String> fields = parseForm(body);
                String operation = fields.remove("operation");
                if (operation == null || operation.isBlank())
                    throw problem(400, "OPERATION_REQUIRED", "operation 필드가 필요합니다.");
                synchronized (backend) { send(exchange, 200, backend.command(operation, Map.copyOf(fields))); }
            }
        } catch (HttpProblem failure) {
            sendError(exchange, failure.status, failure.code, failure.getMessage());
        } catch (BackendException failure) {
            int status = switch (failure.code()) {
                case "STATE_CONFLICT", "TRIP_LOCKED", "NOT_READY", "VEHICLE_NOT_PARKED",
                     "ALREADY_GUIDING", "ROUTE_UNAVAILABLE", "COLLECTION_DISABLED", "CONSENT_NOT_SAVED", "CLOSED" -> 409;
                case "STORAGE_ERROR", "INTERNAL_ERROR" -> 500;
                default -> 400;
            };
            sendError(exchange, status, failure.code(), failure.getMessage());
        } catch (RuntimeException failure) {
            sendError(exchange, 500, "INTERNAL_ERROR", "로컬 백엔드에서 요청을 처리하지 못했습니다.");
        } finally { exchange.close(); }
    }

    static boolean isAllowedHost(String host, int port) {
        return host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port)
                || port == 80 && (host.equals("127.0.0.1") || host.equals("localhost"));
    }

    private static String oneHeader(HttpExchange exchange, String name, boolean required) {
        List<String> values = exchange.getRequestHeaders().get(name);
        if (values == null || values.isEmpty()) {
            if (required) throw problem(400, "HEADER_REQUIRED", name + " 헤더가 필요합니다.");
            return null;
        }
        if (values.size() != 1) throw problem(400, "DUPLICATE_HEADER", "중복 요청 헤더는 허용하지 않습니다.");
        return values.get(0);
    }

    private static void requireFormContentType(String type) {
        if (type == null) throw problem(415, "UNSUPPORTED_MEDIA_TYPE", "폼 형식의 본문이 필요합니다.");
        String[] pieces = type.split(";", -1);
        if (!pieces[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded") || pieces.length > 2)
            throw problem(415, "UNSUPPORTED_MEDIA_TYPE", "application/x-www-form-urlencoded 형식을 사용하세요.");
        if (pieces.length == 2 && !pieces[1].trim().matches("(?i)charset\\s*=\\s*(UTF-8|\"UTF-8\")"))
            throw problem(415, "UNSUPPORTED_CHARSET", "UTF-8 인코딩을 사용하세요.");
    }

    static Map<String, String> parseForm(byte[] body) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (body.length == 0) return fields;
        int start = 0;
        while (start <= body.length) {
            int end = start;
            while (end < body.length && body[end] != '&') end++;
            int equals = start;
            while (equals < end && body[equals] != '=') equals++;
            if (equals == start || equals == end) throw problem(400, "MALFORMED_FORM", "각 필드는 이름=값 형식이어야 합니다.");
            String name = decode(body, start, equals);
            String value = decode(body, equals + 1, end);
            if (name.isBlank()) throw problem(400, "MALFORMED_FORM", "필드 이름이 비어 있습니다.");
            if (fields.putIfAbsent(name, value) != null) throw problem(400, "DUPLICATE_FIELD", "같은 필드를 두 번 보낼 수 없습니다.");
            if (end == body.length) return fields;
            start = end + 1;
        }
        return fields;
    }

    private static String decode(byte[] bytes, int start, int end) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(end - start);
        for (int i = start; i < end; i++) {
            int value = bytes[i] & 255;
            if (value == '%') {
                if (i + 2 >= end) throw problem(400, "MALFORMED_FORM", "잘못된 퍼센트 인코딩입니다.");
                int high = Character.digit((char) (bytes[++i] & 255), 16);
                int low = Character.digit((char) (bytes[++i] & 255), 16);
                if (high < 0 || low < 0) throw problem(400, "MALFORMED_FORM", "잘못된 퍼센트 인코딩입니다.");
                decoded.write(high * 16 + low);
            } else decoded.write(value == '+' ? ' ' : value);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded.toByteArray())).toString();
        } catch (CharacterCodingException failure) {
            throw problem(400, "INVALID_UTF8", "유효한 UTF-8 본문이 필요합니다.");
        }
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message) throws IOException {
        send(exchange, status, Map.of("schemaVersion", SCHEMA_VERSION, "error", Map.of("code", code, "message", message)));
    }

    private static void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = JsonWriter.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static HttpProblem problem(int status, String code, String message) { return new HttpProblem(status, code, message); }
    private static final class HttpProblem extends RuntimeException {
        final int status; final String code;
        HttpProblem(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0); executor.shutdownNow();
        synchronized (backend) { backend.close(); }
    }
}
