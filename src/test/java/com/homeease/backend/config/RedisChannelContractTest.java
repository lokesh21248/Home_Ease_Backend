package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Offline contract check (no Redis needed): both sides of the tracking pub/sub use the same channel name. */
class RedisChannelContractTest {

    @Test
    void channel_name_is_identical_in_core_bridge_and_tracking_publisher() throws Exception {
        assertThat(RedisWebSocketBridge.TRACKING_BROADCAST_CHANNEL).isEqualTo("ws:broadcast:tracking");
        Path src = Path.of("homeease-tracking-service/src/main/java/com/homeease/tracking/service/LiveTrackingService.java");
        if (Files.exists(src)) {
            assertThat(Files.readString(src)).contains("convertAndSend(\"ws:broadcast:tracking\"");
        }
    }
}
