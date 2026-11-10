package kr.routine.bridge;

import kr.routine.presentation.DemoBackend;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/** Command-line launcher. The token is written only to the private local connection file. */
public final class BridgeMain {
    private BridgeMain() {}

    public static void main(String[] args) throws Exception {
        int port = 8791;
        Path runtimeRoot = Path.of("bridge", "runs");
        Path data = null;
        ZoneId zone = ZoneId.of("Asia/Seoul");
        Set<String> origins = new LinkedHashSet<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (key.equals("--help")) {
                System.out.println("BridgeMain [--port 8791] [--origin http://localhost:5173] [--zone Asia/Seoul] [--data state.properties] [--runtime-root bridge/runs]");
                return;
            }
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + key);
            String value = args[++i];
            switch (key) {
                case "--port" -> port = Integer.parseInt(value);
                case "--origin" -> { BridgeServer.validateOriginSetting(value); origins.add(value); }
                case "--runtime-root" -> runtimeRoot = Path.of(value);
                case "--data" -> data = Path.of(value);
                case "--zone" -> zone = ZoneId.of(value);
                default -> throw new IllegalArgumentException("Unknown option: " + key);
            }
        }
        if (origins.isEmpty()) origins.addAll(BridgeServer.DEFAULT_ORIGINS);
        Files.createDirectories(runtimeRoot.toAbsolutePath());
        Path run = Files.createTempDirectory(runtimeRoot.toAbsolutePath(), "run-" + Instant.now().toEpochMilli() + "-");
        if (data == null) data = run.resolve("state.properties");
        DemoBackend backend = new DemoBackend(data, zone);
        BridgeServer bridge;
        try { bridge = new BridgeServer(backend, port, origins); }
        catch (Exception failure) { backend.close(); throw failure; }
        CountDownLatch stop = new CountDownLatch(1);
        Thread hook = new Thread(() -> { bridge.close(); stop.countDown(); }, "routine-local-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try (bridge) {
            Path connection = run.resolve("connection.json");
            Files.writeString(connection, JsonWriter.write(Map.of("baseUrl", bridge.baseUrl(), "token", bridge.token(),
                    "schemaVersion", BridgeServer.SCHEMA_VERSION, "mode", "DEMO")), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.out.println("DEMO backend: " + bridge.baseUrl());
            System.out.println("Local connection file: " + connection);
            stop.await();
        }
    }
}
