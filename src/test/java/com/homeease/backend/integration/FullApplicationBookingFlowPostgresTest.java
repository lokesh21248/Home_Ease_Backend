package com.homeease.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.BookingDto.InstantBookingRequest;
import com.homeease.backend.dto.BookingDto.SubServiceRequestItem;
import com.homeease.backend.model.entity.ServiceEntity;
import com.homeease.backend.model.entity.ServiceablePincode;
import com.homeease.backend.model.entity.SubService;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.model.entity.Worker;
import com.homeease.backend.model.entity.WorkerServiceId;
import com.homeease.backend.model.entity.WorkerServiceLink;
import com.homeease.backend.model.enums.PaymentMode;
import com.homeease.backend.model.enums.UserRole;
import com.homeease.backend.repository.ServiceRepository;
import com.homeease.backend.repository.ServiceablePincodeRepository;
import com.homeease.backend.repository.SubServiceRepository;
import com.homeease.backend.repository.UserRepository;
import com.homeease.backend.repository.WorkerRepository;
import com.homeease.backend.repository.WorkerServiceLinkRepository;
import com.homeease.backend.security.JwtService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The WHOLE application (every bean, controller, security filter, repository query and scheduled job of
 * {@code HomeEaseBackendApplication}) started against a real PostgreSQL, then one real customer and one real worker
 * driven through HTTP, end to end:
 *
 * <pre>
 * POST /bookings/instant -> committed booking -> dispatch -> worker matched -> offer committed -> alerts after commit
 *   -> GET /workers/me/requests -> accept -> customer sees ACCEPTED -> on-the-way -> live location -> arrive
 *   -> customer PIN -> verify PIN (service starts) -> complete
 * </pre>
 *
 * <p>Infrastructure, all throw-away and local (no credential of any kind is involved):
 * <ul>
 *   <li>PostgreSQL 14 from zonky embedded-postgres, schema built by the project's REAL Flyway migrations, with the
 *       production setting {@code ddl-auto=none} (every Spring Data query is still parsed at startup);</li>
 *   <li>Redis: an in-process server (jedis-mock);</li>
 *   <li>Kafka: {@code KAFKA_BOOTSTRAP_SERVERS} left BLANK, i.e. the "no broker provisioned" production situation.</li>
 * </ul>
 *
 * <p>The one thing it cannot be: PostGIS. Embedded PostgreSQL has no PostGIS, and only two migrations need it (V17 and
 * V24: the {@code workers.location} column, its GiST indexes and a sync trigger). Those PostGIS statements are removed
 * from a TEMPORARY COPY of the migrations; every other statement of every migration runs unchanged, and the repository
 * files are never touched. CI's {@code integration-postgis} job is what covers those statements.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class FullApplicationBookingFlowPostgresTest {

    static final String PINCODE = "500081";
    static final double CUSTOMER_LAT = 17.4486, CUSTOMER_LNG = 78.3908;   // Madhapur
    static final double WORKER_LAT = 17.4435, WORKER_LNG = 78.3772;       // Hitec City, ~1.5 km away
    static final AtomicLong PHONES = new AtomicLong(8_000_000_000L);

    private static EmbeddedPostgres postgres;
    private static com.github.fppt.jedismock.RedisServer redis;
    private static Path migrations;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) throws Exception {
        postgres = EmbeddedPostgres.start();
        redis = com.github.fppt.jedismock.RedisServer.newRedisServer().start();
        migrations = migrationsWithoutPostgis();

        // Same driver parameters as production (application.yml: ...?sslmode=require&stringtype=unspecified&prepareThreshold=0).
        // stringtype=unspecified is REQUIRED by the real schema: entities such as Notification map their uuid columns as
        // varchar and several JSONB columns are String fields, which PostgreSQL only accepts when the driver sends strings
        // untyped. Without it every such insert fails here, which production never sees (see the guard test below).
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres") + "&stringtype=unspecified");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "filesystem:" + migrations.toAbsolutePath());
        // production setting (application.yml: ddl-auto none). Hibernate's strict "validate" mode is NOT usable on this
        // schema: it stops at the existing banners.banner_id mismatch (uuid column, mapping expects varchar(36)).
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.data.redis.url", () -> "redis://" + redis.getHost() + ":" + redis.getBindPort());
        registry.add("spring.kafka.bootstrap-servers", () -> "");            // blank: no Kafka broker provisioned
        registry.add("homeease.kafka.outbox.enabled", () -> "false");
        registry.add("homeease.dispatch.recovery.initial-delay-ms", () -> "3600000");
    }

    /** Copies the project's migrations to a temp directory, removing ONLY the PostGIS statements of V17 and V24. */
    private static Path migrationsWithoutPostgis() throws IOException {
        Path source = Path.of("src/main/resources/db/migration/postgresql");
        Path target = Files.createTempDirectory("flyway-no-postgis");
        try (var files = Files.list(source)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".sql"))::iterator) {
                String sql = Files.readString(f, StandardCharsets.UTF_8);
                String name = f.getFileName().toString();
                if (name.startsWith("V24__")) {
                    assertThat(sql).contains("CREATE EXTENSION IF NOT EXISTS postgis");
                    sql = "-- V24 is PostGIS-only (workers.location + GiST indexes): not applicable to the PostGIS-less test database\n";
                } else if (name.startsWith("V17__")) {
                    int from = sql.indexOf("CREATE EXTENSION IF NOT EXISTS postgis;");
                    String endMarker = "EXECUTE FUNCTION sync_worker_location();";
                    int to = sql.indexOf(endMarker);
                    assertThat(from).as("V17 PostGIS section start").isGreaterThanOrEqualTo(0);
                    assertThat(to).as("V17 PostGIS section end").isGreaterThan(from);
                    sql = sql.substring(0, from) + "-- (PostGIS section removed for the PostGIS-less test database)\n"
                            + sql.substring(to + endMarker.length());
                }
                Files.writeString(target.resolve(name), sql, StandardCharsets.UTF_8);
            }
        }
        return target;
    }

    @AfterAll
    static void stopInfrastructure() throws Exception {
        if (redis != null) redis.stop();
        if (postgres != null) postgres.close();
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired ServiceRepository serviceRepository;
    @Autowired SubServiceRepository subServiceRepository;
    @Autowired WorkerRepository workerRepository;
    @Autowired WorkerServiceLinkRepository workerServiceLinkRepository;
    @Autowired ServiceablePincodeRepository pincodeRepository;
    @SpyBean SimpMessagingTemplate stomp;

    /** One customer, one worker who can do exactly this fixture's service, and the catalog entries they share. */
    record Fixture(User customer, User workerUser, Worker worker, ServiceEntity service, SubService subService) { }

    private Fixture fixture(String label) {
        if (!pincodeRepository.existsByPincodeAndIsActiveTrue(PINCODE)) {
            pincodeRepository.save(ServiceablePincode.builder().pincode(PINCODE).areaName("Madhapur").isActive(true).build());
        }
        ServiceEntity service = serviceRepository.save(ServiceEntity.builder()
                .name("Electrician " + label).imageUrl("https://example.invalid/e.png").isActive(true).build());
        SubService sub = subServiceRepository.save(SubService.builder().service(service).name("Fan repair " + label)
                .basePrice(new BigDecimal("199.00")).estimatedMins(30).imageUrl("https://example.invalid/f.png").isActive(true).build());
        User customer = userRepository.save(User.builder().fullName("Customer " + label)
                .phoneNumber("+91" + PHONES.incrementAndGet()).email("c." + PHONES.get() + "@it.invalid").role(UserRole.CUSTOMER).build());
        User workerUser = userRepository.save(User.builder().fullName("Worker " + label)
                .phoneNumber("+91" + PHONES.incrementAndGet()).email("w." + PHONES.get() + "@it.invalid").role(UserRole.WORKER).build());
        Worker worker = workerRepository.save(Worker.builder().user(workerUser).kycStatus("APPROVED").isOnline(true).isVerified(true)
                .currentLat(WORKER_LAT).currentLng(WORKER_LNG).lastLocationUpdatedAt(Instant.now()).workingPincodes(PINCODE).build());
        workerServiceLinkRepository.save(WorkerServiceLink.builder()
                .id(new WorkerServiceId(worker.getWorkerId(), sub.getSubServiceId())).worker(worker).subService(sub).build());
        return new Fixture(customer, workerUser, worker, service, sub);
    }

    // ---- HTTP helpers -----------------------------------------------------------------------------------------------

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", bearer(user));
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return body.isBlank() ? objectMapper.nullNode() : objectMapper.readTree(body);
    }

    private JsonNode createInstantBooking(Fixture f, String idempotencyKey) throws Exception {
        InstantBookingRequest body = InstantBookingRequest.builder()
                .serviceId(f.service().getServiceId())
                .subServices(List.of(SubServiceRequestItem.builder().subServiceId(f.subService().getSubServiceId()).quantity(1).build()))
                .userLat(CUSTOMER_LAT).userLng(CUSTOMER_LNG).paymentMethod(PaymentMode.UPI).pincode(PINCODE).build();
        MockHttpServletRequestBuilder request = as(f.customer(), post("/api/v1/bookings/instant"))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        if (idempotencyKey != null) {
            request.header("X-Idempotency-Key", idempotencyKey);
        }
        return json(mockMvc.perform(request).andExpect(status().isOk()));
    }

    private List<UUID> requestsOf(User workerUser) throws Exception {
        JsonNode list = json(mockMvc.perform(as(workerUser, get("/api/v1/workers/me/requests"))).andExpect(status().isOk()));
        List<UUID> ids = new ArrayList<>();
        list.forEach(n -> ids.add(UUID.fromString(n.get("bookingId").asText())));
        return ids;
    }

    private String bookingStatus(UUID bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE booking_id = ?", String.class, bookingId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private List<String> stompDestinations() {
        return org.mockito.Mockito.mockingDetails(stomp).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("convertAndSend") && i.getArguments().length > 0
                        && i.getArguments()[0] instanceof String)
                .map(i -> (String) i.getArguments()[0])
                .toList();
    }

    // ---- the tests --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("The whole application starts on the schema built by the real Flyway migrations, and every migration succeeded")
    void applicationStarted_onTheRealMigrationChain() {
        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE success = false")).as("failed migrations").isZero();
        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL")).as("applied versioned migrations").isEqualTo(27);
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE version ~ '^[0-9]+$'", Integer.class))
                .isEqualTo(27);
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_name = 'booking_location_history'"))
                .as("V27 created the durable tracking-history table").isEqualTo(1);
    }

    @Test
    @DisplayName("Production's datasource URL still carries stringtype=unspecified, which this test URL mirrors and the real schema depends on")
    void productionDatasourceUrl_keepsStringtypeUnspecified() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        String urlLine = yaml.lines().filter(l -> l.trim().startsWith("url: jdbc:postgresql://")).findFirst().orElse("");
        assertThat(urlLine).as("spring.datasource.url line of application.yml").contains("stringtype=unspecified");
    }

    @Test
    @DisplayName("End to end over HTTP: booking -> dispatch -> worker request -> accept -> on the way -> live location -> arrive -> PIN -> start -> complete")
    void customerBooksAndTheWorkerCompletesTheJob() throws Exception {
        Fixture f = fixture("e2e");

        // 1. customer books: the response is the committed outcome of dispatch
        JsonNode created = createInstantBooking(f, null);
        UUID bookingId = UUID.fromString(created.get("bookingId").asText());
        assertThat(created.get("status").asText()).isEqualTo("SEARCHING");

        // 2. the offer is a committed row that joins to the committed booking and the real worker
        assertThat(bookingStatus(bookingId)).isEqualTo("SEARCHING");
        assertThat(count("SELECT count(*) FROM booking_assignment a JOIN bookings b ON b.booking_id = a.booking_id "
                + "JOIN workers w ON w.worker_id = a.worker_id WHERE a.booking_id = ? AND a.worker_id = ? AND a.status = 'PENDING'",
                bookingId, f.worker().getWorkerId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM dispatch_audit_logs WHERE booking_id = ?", bookingId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM booking_event_outbox WHERE aggregate_id = ? AND event_type = 'BOOKING_CREATED' AND status = 'PENDING'",
                bookingId)).as("outbox row written with the booking, relay disabled").isEqualTo(1);

        // 3. the worker was alerted (after the commit above) and GET /workers/me/requests returns the booking
        assertThat(stompDestinations()).contains("/topic/worker/" + f.worker().getWorkerId() + "/requests");
        assertThat(requestsOf(f.workerUser())).containsExactly(bookingId);
        assertThat(count("SELECT count(*) FROM notification_delivery WHERE booking_id = ? AND worker_id = ?", bookingId, f.worker().getWorkerId()))
                .as("delivery bookkeeping persisted").isEqualTo(2);

        // 4. worker accepts: the customer sees it, the worker's request list is empty again, the PIN is NOT shown to the worker
        JsonNode accepted = json(mockMvc.perform(as(f.workerUser(), post("/api/v1/bookings/" + bookingId + "/accept"))).andExpect(status().isOk()));
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.path("pinCode").isNull() || accepted.path("pinCode").isMissingNode()).isTrue();
        JsonNode seenByCustomer = json(mockMvc.perform(as(f.customer(), get("/api/v1/bookings/" + bookingId + "/status"))).andExpect(status().isOk()));
        assertThat(seenByCustomer.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(seenByCustomer.get("workerAssigned").asBoolean()).isTrue();
        assertThat(requestsOf(f.workerUser())).isEmpty();
        assertThat(stompDestinations()).contains("/topic/booking/" + bookingId);

        // 5. on the way, then live location reaches the customer
        mockMvc.perform(as(f.workerUser(), post("/api/v1/bookings/" + bookingId + "/on-the-way"))).andExpect(status().isOk());
        assertThat(bookingStatus(bookingId)).isEqualTo("ON_THE_WAY");
        long enRouteTs = System.currentTimeMillis() - 90_000;
        mockMvc.perform(as(f.workerUser(), post("/api/v1/tracking/partner/location")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bookingId\":\"" + bookingId + "\",\"lat\":17.4460,\"lng\":78.3840,\"speed\":12.0,\"bearing\":90.0,"
                        + "\"accuracy\":6.0,\"timestamp\":" + enRouteTs + "}"))
                .andExpect(status().isOk());
        // a fix without a device timestamp cannot be ordered or checked for replay: rejected, not stamped "now"
        mockMvc.perform(as(f.workerUser(), post("/api/v1/tracking/partner/location")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bookingId\":\"" + bookingId + "\",\"lat\":17.4461,\"lng\":78.3841}"))
                .andExpect(status().isUnprocessableEntity());
        JsonNode latest = json(mockMvc.perform(as(f.customer(), get("/api/v1/tracking/latest/" + bookingId))).andExpect(status().isOk()));
        assertThat(latest.toString()).as("customer sees the worker's last reported position").contains("17.446").contains("78.384");

        // 6. arrival: refused while the partner is ~750 m away (no PIN is issued), accepted at the door
        mockMvc.perform(as(f.workerUser(), post("/api/v1/bookings/" + bookingId + "/arrive")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_AT_DESTINATION"));
        assertThat(bookingStatus(bookingId)).isEqualTo("ON_THE_WAY");
        assertThat(count("SELECT count(*) FROM booking_otp WHERE booking_id = ?", bookingId)).as("no PIN issued from afar").isZero();
        mockMvc.perform(as(f.workerUser(), post("/api/v1/tracking/partner/location")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bookingId\":\"" + bookingId + "\",\"lat\":17.4487,\"lng\":78.3907,\"speed\":0.0,"
                        + "\"accuracy\":5.0,\"timestamp\":" + (System.currentTimeMillis() - 2_000) + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(as(f.workerUser(), post("/api/v1/bookings/" + bookingId + "/arrive"))).andExpect(status().isOk());
        assertThat(bookingStatus(bookingId)).isEqualTo("ARRIVED");
        String pin = json(mockMvc.perform(as(f.customer(), get("/api/v1/bookings/" + bookingId + "/service-pin"))).andExpect(status().isOk()))
                .get("pinCode").asText();
        assertThat(pin).matches("\\d{4}");
        JsonNode started = json(mockMvc.perform(as(f.workerUser(), post("/api/v1/bookings/" + bookingId + "/verify-pin"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"" + pin + "\"}")).andExpect(status().isOk()));
        assertThat(started.get("bookingStatus").asText()).isEqualTo("IN_PROGRESS");
        assertThat(bookingStatus(bookingId)).isEqualTo("IN_PROGRESS");

        // 7. completion
        JsonNode completed = json(mockMvc.perform(as(f.workerUser(), post("/api/v1/bookings/" + bookingId + "/complete"))).andExpect(status().isOk()));
        assertThat(completed.get("bookingStatus").asText()).isEqualTo("COMPLETED");
        assertThat(bookingStatus(bookingId)).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ? AND status = 'ACCEPTED'", bookingId)).isEqualTo(1);

        // the domain events of the whole journey are in the outbox (relay stays off until a broker exists)
        assertThat(count("SELECT count(*) FROM booking_event_outbox WHERE aggregate_id = ?", bookingId)).isGreaterThanOrEqualTo(4);
        assertThat(count("SELECT count(*) FROM booking_event_outbox WHERE aggregate_id = ? AND status <> 'PENDING'", bookingId)).isZero();
    }

    @Test
    @DisplayName("The same X-Idempotency-Key books once")
    void repeatedRequestWithTheSameIdempotencyKey_isServedOnce() throws Exception {
        Fixture f = fixture("idem");
        String key = UUID.randomUUID().toString();

        UUID first = UUID.fromString(createInstantBooking(f, key).get("bookingId").asText());
        UUID second = UUID.fromString(createInstantBooking(f, key).get("bookingId").asText());

        assertThat(second).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM bookings WHERE user_id = ?", f.customer().getUserId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM booking_assignment WHERE booking_id = ?", first)).isEqualTo(1);
    }

    @Test
    @DisplayName("A cancelled booking disappears from the worker's requests and can no longer be accepted")
    void cancelledBooking_isGoneFromTheWorkersRequests() throws Exception {
        Fixture f = fixture("cancel");
        UUID bookingId = UUID.fromString(createInstantBooking(f, null).get("bookingId").asText());
        assertThat(requestsOf(f.workerUser())).containsExactly(bookingId);

        mockMvc.perform(as(f.customer(), post("/api/v1/bookings/" + bookingId + "/cancel")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"changed my mind\"}")).andExpect(status().isOk());

        assertThat(bookingStatus(bookingId)).isEqualTo("CANCELLED");
        assertThat(requestsOf(f.workerUser())).isEmpty();
        int accept = mockMvc.perform(as(f.workerUser(), post("/api/v1/bookings/" + bookingId + "/accept"))).andReturn().getResponse().getStatus();
        assertThat(accept).as("accepting a cancelled booking is refused (HTTP " + accept + ")").isBetween(400, 499);
        assertThat(bookingStatus(bookingId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("Security: no token, a forged token, or a non-admin on an admin route are all refused")
    void securityRules() throws Exception {
        Fixture f = fixture("sec");
        mockMvc.perform(get("/api/v1/workers/me/requests")).andExpect(status().is4xxClientError());
        int forged = mockMvc.perform(get("/api/v1/workers/me/requests").header("Authorization", "Bearer not.a.token"))
                .andReturn().getResponse().getStatus();
        assertThat(forged).isIn(401, 403);
        mockMvc.perform(as(f.customer(), get("/api/v1/admin/bookings"))).andExpect(status().isForbidden());
        mockMvc.perform(as(f.workerUser(), get("/api/v1/admin/bookings"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/bookings/instant").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
    }
}
