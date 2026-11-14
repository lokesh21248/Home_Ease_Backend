package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fppt.jedismock.RedisServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class RedisWebSocketBridgeTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SimpMessagingTemplate stomp;
    private RedisWebSocketBridge bridge;

    @BeforeEach
    void setUp() {
        stomp = mock(SimpMessagingTemplate.class);
        bridge = new RedisWebSocketBridge(stomp, mapper);
    }

    /** Envelope exactly as LiveTrackingService publishes it. */
    private String message(String bookingId, String workerId, long ts, double lat) {
        return "{\"destination\":\"/topic/tracking/" + bookingId + "\",\"bookingId\":\"" + bookingId + "\","
                + "\"payload\":{\"bookingId\":\"" + bookingId + "\",\"partnerId\":\"" + workerId + "\","
                + "\"lat\":" + lat + ",\"lng\":77.5,\"timestamp\":" + ts + ",\"sequenceNumber\":" + ts + "}}";
    }

    @Test
    void forwards_to_the_booking_topic_and_its_alias_with_the_worker_mapping_intact() throws Exception {
        assertThat(bridge.relay(message("B-1", "W-9", 1000, 12.5))).isTrue();
        verify(stomp).convertAndSend(eq("/topic/tracking/B-1"), any(JsonNode.class));
        verify(stomp).convertAndSend(eq("/topic/track/B-1"), any(JsonNode.class));

        org.mockito.ArgumentCaptor<JsonNode> payload = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(stomp, times(2)).convertAndSend(any(String.class), payload.capture());
        assertThat(payload.getValue().path("partnerId").asText()).isEqualTo("W-9");
        assertThat(payload.getValue().path("bookingId").asText()).isEqualTo("B-1");
        assertThat(bridge.forwardedCount()).isEqualTo(1);
    }

    @Test
    void two_bookings_never_cross_over() {
        bridge.relay(message("B-1", "W-1", 1000, 1.0));
        bridge.relay(message("B-2", "W-2", 1000, 2.0));
        verify(stomp, times(1)).convertAndSend(eq("/topic/tracking/B-1"), any(JsonNode.class));
        verify(stomp, times(1)).convertAndSend(eq("/topic/tracking/B-2"), any(JsonNode.class));
        verify(stomp, times(1)).convertAndSend(eq("/topic/track/B-1"), any(JsonNode.class));
        verify(stomp, times(1)).convertAndSend(eq("/topic/track/B-2"), any(JsonNode.class));
    }

    @Test
    void a_destination_that_does_not_match_the_envelope_booking_id_is_dropped() {
        String forged = "{\"destination\":\"/topic/tracking/VICTIM\",\"bookingId\":\"B-1\",\"payload\":{\"lat\":1,\"timestamp\":5}}";
        assertThat(bridge.relay(forged)).isFalse();
        verify(stomp, never()).convertAndSend(any(String.class), any(Object.class));
        assertThat(bridge.droppedMismatchCount()).isEqualTo(1);
    }

    @Test
    void a_payload_for_a_different_booking_than_its_envelope_is_dropped() {
        String mixed = "{\"destination\":\"/topic/tracking/B-1\",\"bookingId\":\"B-1\","
                + "\"payload\":{\"bookingId\":\"B-2\",\"lat\":1,\"timestamp\":5}}";
        assertThat(bridge.relay(mixed)).isFalse();
        verify(stomp, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void arbitrary_destinations_are_never_forwarded() {
        // e.g. an attempt to push into a worker's private topic or another namespace
        String other = "{\"destination\":\"/topic/worker/W-1/requests\",\"bookingId\":\"B-1\",\"payload\":{\"x\":1}}";
        assertThat(bridge.relay(other)).isFalse();
        verify(stomp, never()).convertAndSend(startsWith("/topic/worker"), any(Object.class));
    }

    @Test
    void duplicates_and_out_of_order_events_are_dropped_but_newer_ones_pass() {
        assertThat(bridge.relay(message("B-1", "W-1", 2000, 1.0))).isTrue();
        assertThat(bridge.relay(message("B-1", "W-1", 2000, 1.0))).as("exact redelivery").isFalse();
        assertThat(bridge.relay(message("B-1", "W-1", 1500, 0.9))).as("older event arriving late").isFalse();
        assertThat(bridge.relay(message("B-1", "W-1", 2500, 1.1))).as("newer event").isTrue();
        assertThat(bridge.droppedStaleCount()).isEqualTo(2);
        verify(stomp, times(2)).convertAndSend(eq("/topic/tracking/B-1"), any(JsonNode.class));
    }

    @Test
    void ordering_state_is_per_booking() {
        assertThat(bridge.relay(message("B-1", "W-1", 9000, 1.0))).isTrue();
        assertThat(bridge.relay(message("B-2", "W-2", 10, 2.0))).as("B-2 has its own clock").isTrue();
    }

    @Test
    void malformed_or_incomplete_messages_are_dropped_without_throwing() {
        assertThat(bridge.relay("not json")).isFalse();
        assertThat(bridge.relay("{}")).isFalse();
        assertThat(bridge.relay("{\"destination\":\"/topic/tracking/B\",\"bookingId\":\"B\"}")).isFalse();
        verify(stomp, never()).convertAndSend(any(String.class), any(Object.class));
        assertThat(bridge.droppedMalformedCount()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- real pub/sub + recovery

    @Test
    void end_to_end_publish_from_tracking_style_template_reaches_the_stomp_broker_and_survives_a_redis_outage() throws Exception {
        RedisServer server = RedisServer.newRedisServer().start();
        int port = server.getBindPort();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port));
        factory.afterPropertiesSet();
        RedisMessageListenerContainer container = bridge.redisMessageListenerContainer(factory);
        container.setRecoveryInterval(500L); // production default is 5000ms; shortened only to keep the test fast
        container.afterPropertiesSet();
        container.start();
        StringRedisTemplate publisher = new StringRedisTemplate(factory);
        try {
            // 1. normal delivery (subscription is asynchronous, so publish until the bridge has seen it)
            long deadline = System.currentTimeMillis() + 10_000;
            long ts = 1;
            while (bridge.forwardedCount() == 0 && System.currentTimeMillis() < deadline) {
                publisher.convertAndSend(RedisWebSocketBridge.TRACKING_BROADCAST_CHANNEL, message("B-E2E", "W-1", ts++, 3.0));
                Thread.sleep(300);
            }
            assertThat(bridge.forwardedCount()).as("message published over real pub/sub reaches the bridge").isGreaterThan(0);
            verify(stomp, atLeastOnce()).convertAndSend(eq("/topic/tracking/B-E2E"), any(JsonNode.class));

            // 2. Redis outage, then a NEW server on the same port: the container must resubscribe by itself
            server.stop();
            Thread.sleep(1000);
            RedisServer restarted = RedisServer.newRedisServer(port).start();
            try {
                long before = bridge.forwardedCount();
                long deadline2 = System.currentTimeMillis() + 20_000;
                while (bridge.forwardedCount() == before && System.currentTimeMillis() < deadline2) {
                    try {
                        publisher.convertAndSend(RedisWebSocketBridge.TRACKING_BROADCAST_CHANNEL,
                                message("B-E2E", "W-1", 1_000_000 + ts++, 3.1));
                    } catch (Exception stillReconnecting) {
                        // publisher connection also recovering
                    }
                    Thread.sleep(500);
                }
                assertThat(bridge.forwardedCount()).as("bridge resubscribed after the outage").isGreaterThan(before);
            } finally {
                restarted.stop();
            }
        } finally {
            container.stop();
            container.destroy();
            factory.destroy();
            try { server.stop(); } catch (Exception ignored) { }
        }
    }
}
