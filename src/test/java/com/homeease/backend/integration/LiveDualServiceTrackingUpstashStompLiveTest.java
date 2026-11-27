package com.homeease.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.model.entity.ServiceEntity;
import com.homeease.backend.model.entity.ServiceablePincode;
import com.homeease.backend.model.entity.SubService;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.entity.WorkerServiceId;
import com.homeease.backend.model.entity.WorkerServiceLink;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.ServiceRepository;
import com.homeease.backend.repository.ServiceablePincodeRepository;
import com.homeease.backend.repository.SubServiceRepository;
import com.homeease.backend.repository.UserRepository;
import com.homeease.backend.repository.WorkerRepository;
import com.homeease.backend.repository.WorkerServiceLinkRepository;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * LIVE DUAL-SERVICE E2E VERIFICATION TEST:
 * Real Core Spring Boot (TOMCAT + STOMP broker + EmbeddedPostgres + Flyway)
 * <-> Real Upstash TLS Redis (rediss://) + Pub/Sub ws:broadcast:tracking
 * <-> Real Tracking Microservice JAR subprocess
 * <-> Real Customer & Stranger WebSocketStompClient connections.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class LiveDualServiceTrackingUpstashStompLiveTest {

    private static final String REDIS_URL = System.getenv("REDIS_URL");
    private static final String ALLOW_SHARED = System.getenv("REDIS_LIVE_ALLOW_SHARED_KEYS");
    private static final String SHARED_JWT_SECRET =
            "live-dual-e2e-shared-jwt-hmac-secret-key-0123456789abcdef01234567";
    private static final String PINCODE = "500081";
    private static final double CUSTOMER_LAT = 17.4486;
    private static final double CUSTOMER_LNG = 78.3908;
    private static final double WORKER_START_LAT = 17.4477;
    private static final double WORKER_START_LNG = 78.3896;
    private static final AtomicLong PHONE_SEQ = new AtomicLong(7_910_000_000L);

    private static EmbeddedPostgres postgres;
    private static Path migrations;
    private static HttpServer routesStubServer;
    private static Process trackingProcess;
    private static int trackingPort;

    @LocalServerPort
    private int corePort;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private RedisTemplate<String, String> redisTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private SubServiceRepository subServiceRepository;
    @Autowired private WorkerRepository workerRepository;
    @Autowired private WorkerServiceLinkRepository workerServiceLinkRepository;
    @Autowired private ServiceablePincodeRepository pincodeRepository;

    @DynamicPropertySource
    static void initLiveDualInfrastructure(DynamicPropertyRegistry registry) throws Exception {
        assumeTrue(REDIS_URL != null && !REDIS_URL.isBlank(), "REDIS_URL not set - live dual E2E skipped");
        assumeTrue("true".equalsIgnoreCase(ALLOW_SHARED), "REDIS_LIVE_ALLOW_SHARED_KEYS!=true - skipped");

        postgres = EmbeddedPostgres.start();
        migrations = migrationsWithoutPostgis();

        routesStubServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        routesStubServer.createContext("/directions/v2:computeRoutes", exchange -> {
            byte[] resp = ("{\"routes\":[{\"distanceMeters\":1480,\"duration\":\"240s\",\"staticDuration\":\"210s\","
                    + "\"polyline\":{\"encodedPolyline\":\"mfmiB{td}MuAcCm@wA\"},\"legs\":[{\"steps\":[]}]}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        routesStubServer.start();
        int stubPort = routesStubServer.getAddress().getPort();

        try (ServerSocket ss = new ServerSocket(0)) {
            trackingPort = ss.getLocalPort();
        }
        Path jar = Path.of("homeease-tracking-service/target/homeease-tracking-service-1.0.0-SNAPSHOT.jar")
                .toAbsolutePath();
        assertThat(Files.exists(jar))
                .as("Tracking JAR must exist at " + jar + " (run mvn -f homeease-tracking-service/pom.xml package -DskipTests)")
                .isTrue();

        String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
        java.io.File logFile = java.io.File.createTempFile("tracking-service-live-", ".log");
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-jar", jar.toString(),
                "--server.port=" + trackingPort,
                "--spring.data.redis.url=" + REDIS_URL,
                "--homeease.jwt.secret=" + SHARED_JWT_SECRET,
                "--homeease.tracking.require-assignment=true");
        pb.redirectOutput(logFile);
        pb.redirectError(logFile);
        trackingProcess = pb.start();

        // Wait up to 45s for tracking microservice HTTP health
        HttpClient probe = HttpClient.newHttpClient();
        boolean up = false;
        for (int i = 0; i < 90; i++) {
            if (!trackingProcess.isAlive()) {
                String logs = Files.exists(logFile.toPath()) ? Files.readString(logFile.toPath()) : "";
                throw new IllegalStateException("Tracking process exited early with code " + trackingProcess.exitValue() + "\nLogs:\n" + logs);
            }
            try {
                HttpResponse<String> r = probe.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + trackingPort + "/actuator/health"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    up = true;
                    break;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(500);
        }
        if (!up) {
            String logs = Files.exists(logFile.toPath()) ? Files.readString(logFile.toPath()) : "";
            assertThat(up).as("Tracking microservice booted on port " + trackingPort + "\nLogs:\n" + logs).isTrue();
        }

        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres") + "&stringtype=unspecified");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "filesystem:" + migrations.toAbsolutePath());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.data.redis.url", () -> REDIS_URL);
        registry.add("spring.kafka.bootstrap-servers", () -> "");
        registry.add("homeease.kafka.outbox.enabled", () -> "false");
        registry.add("homeease.dispatch.recovery.initial-delay-ms", () -> "3600000");
        registry.add("homeease.jwt.secret", () -> SHARED_JWT_SECRET);
        registry.add("homeease.routes.google.base-url", () -> "http://127.0.0.1:" + stubPort);
        registry.add("homeease.routes.google.api-key", () -> "e2e-stub-key");
    }

    private static Path migrationsWithoutPostgis() throws IOException {
        Path source = Path.of("src/main/resources/db/migration/postgresql");
        Path target = Files.createTempDirectory("flyway-live-e2e");
        try (var files = Files.list(source)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".sql"))::iterator) {
                String sql = Files.readString(f, StandardCharsets.UTF_8);
                String name = f.getFileName().toString();
                if (name.startsWith("V24__")) {
                    sql = "-- V24 skipped\n";
                } else if (name.startsWith("V17__")) {
                    int from = sql.indexOf("CREATE EXTENSION IF NOT EXISTS postgis;");
                    String endMarker = "EXECUTE FUNCTION sync_worker_location();";
                    int to = sql.indexOf(endMarker);
                    sql = sql.substring(0, from) + "-- PostGIS skipped\n" + sql.substring(to + endMarker.length());
                }
                Files.writeString(target.resolve(name), sql, StandardCharsets.UTF_8);
            }
        }
        return target;
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (trackingProcess != null && trackingProcess.isAlive()) {
            trackingProcess.destroyForcibly();
        }
        if (routesStubServer != null) {
            routesStubServer.stop(0);
        }
        if (postgres != null) {
            postgres.close();
        }
    }

    private String otpLogin(HttpClient http, String phone) throws Exception {
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + corePort + "/api/v1/auth/otp/send"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"phoneNumber\":\"" + phone + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        String otp = redisTemplate.opsForValue().get("auth:otp:" + phone);
        if (otp == null || otp.isBlank()) {
            otp = "123456"; // fallback for test profile
        }
        HttpResponse<String> verify = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + corePort + "/api/v1/auth/otp/verify"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"phoneNumber\":\"" + phone + "\",\"otpCode\":\"" + otp + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(verify.statusCode()).isEqualTo(200);
        JsonNode root = objectMapper.readTree(verify.body());
        String token = root.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    @Test
    @DisplayName("Full Live Dual-Service E2E: Real OTP JWT -> Booking -> OnTheWay -> 3 GPS -> Upstash -> PubSub -> STOMP -> Route -> Arrive -> PIN -> Verify")
    void fullDualServiceLiveStompAndUpstashJourney() throws Exception {
        if (!pincodeRepository.existsByPincodeAndIsActiveTrue(PINCODE)) {
            pincodeRepository.save(ServiceablePincode.builder().pincode(PINCODE).areaName("Madhapur").isActive(true).build());
        }
        String custPhone = "+91" + PHONE_SEQ.incrementAndGet();
        String workerPhone = "+91" + PHONE_SEQ.incrementAndGet();
        String strangerPhone = "+91" + PHONE_SEQ.incrementAndGet();

        User customer = userRepository.save(User.builder().fullName("E2E Customer")
                .phoneNumber(custPhone).email("c" + PHONE_SEQ.get() + "@e2e.invalid").role(UserRole.CUSTOMER).build());
        User stranger = userRepository.save(User.builder().fullName("E2E Stranger")
                .phoneNumber(strangerPhone).email("s" + PHONE_SEQ.get() + "@e2e.invalid").role(UserRole.CUSTOMER).build());
        User workerUser = userRepository.save(User.builder().fullName("E2E Worker")
                .phoneNumber(workerPhone).email("w" + PHONE_SEQ.get() + "@e2e.invalid").role(UserRole.WORKER).build());
        Worker worker = workerRepository.save(Worker.builder().user(workerUser).kycStatus("APPROVED")
                .isOnline(true).isVerified(true).currentLat(WORKER_START_LAT).currentLng(WORKER_START_LNG)
                .lastLocationUpdatedAt(Instant.now()).workingPincodes(PINCODE).build());
        ServiceEntity svc = serviceRepository.save(ServiceEntity.builder().name("E2E Plumbing")
                .imageUrl("https://example.invalid/p.png").isActive(true).build());
        SubService sub = subServiceRepository.save(SubService.builder().service(svc).name("Pipe Fix")
                .basePrice(new BigDecimal("299.00")).estimatedMins(30).imageUrl("https://example.invalid/s.png")
                .isActive(true).build());
        workerServiceLinkRepository.save(WorkerServiceLink.builder()
                .id(new WorkerServiceId(worker.getWorkerId(), sub.getSubServiceId())).worker(worker).subService(sub).build());

        HttpClient http = HttpClient.newHttpClient();
        String customerJwt = otpLogin(http, custPhone);
        String workerJwt = otpLogin(http, workerPhone);
        String strangerJwt = otpLogin(http, strangerPhone);

        String coreBase = "http://127.0.0.1:" + corePort + "/api/v1";
        String trackingBase = "http://127.0.0.1:" + trackingPort + "/api/v1";

        UUID bookingId = null;
        WebSocketStompClient customerStompClient = new WebSocketStompClient(new StandardWebSocketClient());
        customerStompClient.setMessageConverter(new MappingJackson2MessageConverter());
        WebSocketStompClient strangerStompClient = new WebSocketStompClient(new StandardWebSocketClient());
        strangerStompClient.setMessageConverter(new MappingJackson2MessageConverter());

        try {
            // 1. Customer books
            String bookBody = objectMapper.writeValueAsString(Map.of(
                    "serviceId", svc.getServiceId().toString(),
                    "subServices", List.of(Map.of("subServiceId", sub.getSubServiceId().toString(), "quantity", 1)),
                    "userLat", CUSTOMER_LAT,
                    "userLng", CUSTOMER_LNG,
                    "paymentMethod", "UPI",
                    "pincode", PINCODE));
            HttpResponse<String> booked = http.send(HttpRequest.newBuilder(URI.create(coreBase + "/bookings/instant"))
                            .header("Authorization", "Bearer " + customerJwt)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(bookBody)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(booked.statusCode()).isEqualTo(200);
            bookingId = UUID.fromString(objectMapper.readTree(booked.body()).get("bookingId").asText());

            // 2. Worker accepts
            HttpResponse<String> accepted = http.send(HttpRequest.newBuilder(
                            URI.create(coreBase + "/bookings/" + bookingId + "/accept"))
                            .header("Authorization", "Bearer " + workerJwt)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(accepted.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(accepted.body()).get("status").asText()).isEqualTo("ACCEPTED");

            // 3. Before Start Travelling: Tracking microservice rejects with 409 TRACKING_NOT_ACTIVE
            long t0 = System.currentTimeMillis() - 15_000;
            HttpResponse<String> earlyGps = postGps(http, trackingBase, workerJwt, bookingId, 17.4440, 78.3785, t0);
            assertThat(earlyGps.statusCode()).isEqualTo(409);
            assertThat(objectMapper.readTree(earlyGps.body()).path("code").asText()).isEqualTo("TRACKING_NOT_ACTIVE");

            // 4. Worker Start Travelling (plus idempotent retry) -> ON_THE_WAY + Redis trackingActive=true
            for (int r = 0; r < 2; r++) {
                HttpResponse<String> travel = http.send(HttpRequest.newBuilder(
                                URI.create(coreBase + "/workers/bookings/" + bookingId + "/on-the-way"))
                                .header("Authorization", "Bearer " + workerJwt)
                                .POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(travel.statusCode()).isEqualTo(200);
                assertThat(objectMapper.readTree(travel.body()).get("status").asText()).isEqualTo("ON_THE_WAY");
            }

            String assignRaw = redisTemplate.opsForValue().get("tracking:assignment:" + bookingId);
            JsonNode assignNode = objectMapper.readTree(assignRaw);
            assertThat(assignNode.path("status").asText()).isEqualTo("ON_THE_WAY");
            assertThat(assignNode.path("trackingActive").asBoolean()).isTrue();

            // 5. Customer & Stranger open REAL WebSocket STOMP connections
            BlockingQueue<Map<String, Object>> customerLocFrames = new LinkedBlockingQueue<>();
            BlockingQueue<Map<String, Object>> bookingLifecycleFrames = new LinkedBlockingQueue<>();
            BlockingQueue<Map<String, Object>> strangerFrames = new LinkedBlockingQueue<>();

            StompHeaders custHeaders = new StompHeaders();
            custHeaders.add("Authorization", "Bearer " + customerJwt);
            CountDownLatch custReady = new CountDownLatch(1);
            String finalBookingStr = bookingId.toString();

            StompSession custSession = customerStompClient.connectAsync(
                    "ws://127.0.0.1:" + corePort + "/ws",
                    new WebSocketHttpHeaders(),
                    custHeaders,
                    new StompSessionHandlerAdapter() {
                        @Override
                        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                            session.subscribe("/topic/tracking/" + finalBookingStr, new StompFrameHandler() {
                                @Override public Type getPayloadType(StompHeaders h) { return Map.class; }
                                @SuppressWarnings("unchecked")
                                @Override public void handleFrame(StompHeaders h, Object p) {
                                    customerLocFrames.add((Map<String, Object>) p);
                                }
                            });
                            session.subscribe("/topic/booking/" + finalBookingStr, new StompFrameHandler() {
                                @Override public Type getPayloadType(StompHeaders h) { return Map.class; }
                                @SuppressWarnings("unchecked")
                                @Override public void handleFrame(StompHeaders h, Object p) {
                                    bookingLifecycleFrames.add((Map<String, Object>) p);
                                }
                            });
                            custReady.countDown();
                        }
                    }).get(10, TimeUnit.SECONDS);
            assertThat(custReady.await(5, TimeUnit.SECONDS)).isTrue();

            // Stranger session connects cleanly, tries to subscribe to customer's booking topic, receives ZERO frames
            StompHeaders strangerHeaders = new StompHeaders();
            strangerHeaders.add("Authorization", "Bearer " + strangerJwt);
            try {
                StompSession strangerSession = strangerStompClient.connectAsync(
                        "ws://127.0.0.1:" + corePort + "/ws",
                        new WebSocketHttpHeaders(),
                        strangerHeaders,
                        new StompSessionHandlerAdapter() {}).get(10, TimeUnit.SECONDS);
                strangerSession.subscribe("/topic/tracking/" + finalBookingStr, new StompFrameHandler() {
                    @Override public Type getPayloadType(StompHeaders h) { return Map.class; }
                    @SuppressWarnings("unchecked")
                    @Override public void handleFrame(StompHeaders h, Object p) {
                        strangerFrames.add((Map<String, Object>) p);
                    }
                });
            } catch (Exception ignored) {
                // Closing on STOMP ERROR is valid too
            }

            Thread.sleep(300);

            // 6. Send 3 progressive GPS fixes via Tracking service -> Upstash -> PubSub -> Core -> STOMP Customer
            double[][] coords = {
                    { 17.4480, 78.3900 },
                    { 17.4483, 78.3904 },
                    { CUSTOMER_LAT, CUSTOMER_LNG }
            };
            List<Long> receivedSeqs = new ArrayList<>();
            for (int i = 0; i < coords.length; i++) {
                long fixTs = System.currentTimeMillis() - ((coords.length - i) * 3500L);
                HttpResponse<String> r = postGps(http, trackingBase, workerJwt, bookingId,
                        coords[i][0], coords[i][1], fixTs);
                assertThat(r.statusCode()).isEqualTo(200);

                Map<String, Object> msg = customerLocFrames.poll(10, TimeUnit.SECONDS);
                assertThat(msg).as("STOMP MESSAGE frame #" + (i + 1) + " arrived via Upstash Pub/Sub").isNotNull();
                Number seqNum = (Number) msg.get("sequenceNumber");
                assertThat(seqNum.longValue()).isEqualTo(i + 1L);
                assertThat(msg.get("etaMinutes")).isNull();
                assertThat(msg.get("distanceKm")).isNull();
                receivedSeqs.add(seqNum.longValue());
            }
            assertThat(receivedSeqs).containsExactly(1L, 2L, 3L);
            assertThat(strangerFrames).isEmpty();

            // Verify Redis keys + TTLs directly in Upstash TLS
            assertThat(redisTemplate.getExpire("tracking:booking:" + bookingId)).isBetween(1L, 7200L);
            assertThat(redisTemplate.getExpire("homeease:tracking:" + bookingId + ":location")).isBetween(1L, 120L);
            assertThat(redisTemplate.opsForValue().get("tracking:seq:" + bookingId)).isEqualTo("3");

            // 7. Verify Tracking /latest/{id} Date header + Core /snapshot + Core /route (Google Routes v2 contract)
            HttpResponse<String> latestResp = http.send(HttpRequest.newBuilder(
                            URI.create(trackingBase + "/tracking/latest/" + bookingId))
                            .header("Authorization", "Bearer " + customerJwt).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(latestResp.statusCode()).isEqualTo(200);
            assertThat(latestResp.headers().firstValue("Date")).isPresent();
            assertThat(objectMapper.readTree(latestResp.body()).path("sequenceNumber").asLong()).isEqualTo(3L);

            HttpResponse<String> snapResp = http.send(HttpRequest.newBuilder(
                            URI.create(coreBase + "/tracking/" + bookingId))
                            .header("Authorization", "Bearer " + customerJwt).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(snapResp.statusCode()).isEqualTo(200);
            JsonNode snapNode = objectMapper.readTree(snapResp.body());
            assertThat(snapNode.path("live").asBoolean()).isTrue();
            assertThat(snapNode.path("workerLocation").path("lat").asDouble()).isEqualTo(CUSTOMER_LAT);

            HttpResponse<String> routeResp = http.send(HttpRequest.newBuilder(
                            URI.create(coreBase + "/tracking/" + bookingId + "/route?reason=INITIAL"))
                            .header("Authorization", "Bearer " + customerJwt).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(routeResp.statusCode()).isEqualTo(200);
            JsonNode routeNode = objectMapper.readTree(routeResp.body());
            assertThat(routeNode.path("found").asBoolean()).isTrue();
            assertThat(routeNode.path("polyline").asText()).isNotBlank();
            assertThat(routeNode.path("distanceMeters").asLong()).isEqualTo(1480L);

            // 8. Worker arrives -> Tracking stopped + genuine PIN + 410 on subsequent GPS + Verify PIN
            HttpResponse<String> arrive = http.send(HttpRequest.newBuilder(
                            URI.create(coreBase + "/workers/bookings/" + bookingId + "/arrive"))
                            .header("Authorization", "Bearer " + workerJwt)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(arrive.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(arrive.body()).get("status").asText()).isEqualTo("ARRIVED");

            Map<String, Object> arrivalEvt = bookingLifecycleFrames.poll(5, TimeUnit.SECONDS);
            assertThat(arrivalEvt).isNotNull();

            // Post-arrival GPS rejected with 410 TRACKING_CLOSED
            HttpResponse<String> afterArriveGps = postGps(http, trackingBase, workerJwt, bookingId,
                    CUSTOMER_LAT, CUSTOMER_LNG, System.currentTimeMillis());
            assertThat(afterArriveGps.statusCode()).isEqualTo(410);
            assertThat(objectMapper.readTree(afterArriveGps.body()).path("code").asText()).isEqualTo("TRACKING_CLOSED");

            // Customer fetches genuine 4-digit PIN (Worker forbidden)
            HttpResponse<String> pinResp = http.send(HttpRequest.newBuilder(
                            URI.create(coreBase + "/bookings/" + bookingId + "/service-pin"))
                            .header("Authorization", "Bearer " + customerJwt).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(pinResp.statusCode()).isEqualTo(200);
            String pin = objectMapper.readTree(pinResp.body()).path("pinCode").asText();
            assertThat(pin).matches("\\d{4}");

            HttpResponse<String> verifyBad = http.send(HttpRequest.newBuilder(
                            URI.create(coreBase + "/bookings/" + bookingId + "/verify-pin"))
                            .header("Authorization", "Bearer " + workerJwt)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"pin\":\"0000\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(verifyBad.statusCode()).isBetween(400, 499);

            HttpResponse<String> verifyOk = http.send(HttpRequest.newBuilder(
                            URI.create(coreBase + "/bookings/" + bookingId + "/verify-pin"))
                            .header("Authorization", "Bearer " + workerJwt)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"pin\":\"" + pin + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(verifyOk.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(verifyOk.body()).path("bookingStatus").asText()).isEqualTo("IN_PROGRESS");

            custSession.disconnect();
            System.out.println("[LIVE-DUAL-E2E] ALL 12 LIVE PIPELINE STAGES PASSED (Core :" + corePort
                    + ", Tracking :" + trackingPort + ", Upstash TLS, Real STOMP MESSAGEs)");
        } finally {
            customerStompClient.stop();
            strangerStompClient.stop();
            if (bookingId != null) {
                redisTemplate.delete(List.of(
                        "tracking:assignment:" + bookingId,
                        "tracking:booking:" + bookingId,
                        "homeease:tracking:" + bookingId + ":location",
                        "tracking:seq:" + bookingId,
                        "tracking:session:" + bookingId,
                        "tracking:route:" + bookingId,
                        "tracking:device:" + bookingId,
                        "auth:otp:" + custPhone,
                        "auth:otp:" + workerPhone,
                        "auth:otp:" + strangerPhone));
                redisTemplate.opsForGeo().remove("active_workers_geo", worker.getWorkerId().toString(),
                        workerUser.getUserId().toString());
            }
        }
    }

    private static HttpResponse<String> postGps(
            HttpClient http, String trackingBase, String jwt, UUID bookingId, double lat, double lng, long ts)
            throws Exception {
        String body = String.format(java.util.Locale.US,
                "{\"bookingId\":\"%s\",\"lat\":%.6f,\"lng\":%.6f,\"bearing\":45.0,\"speed\":6.5,\"accuracy\":7.0,\"timestamp\":%d}",
                bookingId, lat, lng, ts);
        return http.send(HttpRequest.newBuilder(URI.create(trackingBase + "/tracking/partner/location"))
                        .header("Authorization", "Bearer " + jwt)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
