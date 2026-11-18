package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.homeease.backend.repository.BookingEventOutboxRepository;
import com.homeease.backend.service.BookingOutboxPoller;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Kafka must stay OPTIONAL: with no broker provisioned the application has to start and stay quiet. No broker is
 * involved anywhere in this class (nothing listens on the addresses used here).
 *
 * <p>It pins down the three ways the previous configuration got this wrong:
 * <ul>
 *   <li>{@code spring.kafka.listener.auto-startup=false} was ignored (the project defines its own listener container
 *       factory, which Spring Boot's auto-configuration then does not touch), so the consumers always started;</li>
 *   <li>a BLANK {@code KAFKA_BOOTSTRAP_SERVERS} made the consumers fail to start, which stopped the application;</li>
 *   <li>{@code KafkaTemplate.send} blocked for Kafka's default 60 s per call while the broker was down.</li>
 * </ul>
 */
class KafkaOptionalBrokerTest {

    @Configuration
    static class ListenerFixture {
        @Bean
        Listener listener() {
            return new Listener();
        }
    }

    static class Listener {
        @KafkaListener(topics = "kafka-optional-broker-test", groupId = "kafka-optional-broker-test",
                containerFactory = "kafkaListenerContainerFactory")
        public void on(String message) {
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaConfig.class, ListenerFixture.class);

    private static boolean anyContainerRunning(org.springframework.context.ApplicationContext context) {
        KafkaListenerEndpointRegistry registry = context.getBean(KafkaListenerEndpointRegistry.class);
        assertThat(registry.getListenerContainers()).as("the @KafkaListener was registered").isNotEmpty();
        return registry.getListenerContainers().stream().anyMatch(c -> c.isRunning());
    }

    @Test
    @DisplayName("a BLANK KAFKA_BOOTSTRAP_SERVERS does not stop the application, even with auto-startup switched on")
    void blankBroker_applicationStartsAndNothingConnects() {
        runner.withPropertyValues(
                        "spring.kafka.bootstrap-servers=",
                        "spring.kafka.listener.auto-startup=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(anyContainerRunning(context)).as("no broker, so no consumer").isFalse();
                });
    }

    @Test
    @DisplayName("nothing configured at all: starts, consumers stay off (auto-startup defaults to false)")
    void defaults_noBroker_consumersStayOff() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(anyContainerRunning(context)).isFalse();
        });
    }

    @Test
    @DisplayName("spring.kafka.listener.auto-startup=false is honoured even when a broker address is configured")
    void autoStartupFalse_isHonoured() {
        runner.withPropertyValues(
                        "spring.kafka.bootstrap-servers=127.0.0.1:1",
                        "spring.kafka.listener.auto-startup=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(anyContainerRunning(context)).isFalse();
                });
    }

    @Test
    @DisplayName("auto-startup=true with a broker address does start the consumers (the switch really works both ways)")
    void autoStartupTrue_withBroker_startsConsumers() {
        runner.withPropertyValues(
                        "spring.kafka.bootstrap-servers=127.0.0.1:1",
                        "spring.kafka.listener.auto-startup=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(anyContainerRunning(context)).isTrue();
                });
    }

    @Test
    @DisplayName("a send to an unreachable broker fails within seconds, not after Kafka's default 60 s")
    void sendWithoutBroker_failsFast() {
        runner.withPropertyValues("spring.kafka.bootstrap-servers=127.0.0.1:1")
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    KafkaTemplate<String, String> template = context.getBean(KafkaTemplate.class);
                    assertThat(template.getProducerFactory().getConfigurationProperties().get("max.block.ms")).isEqualTo(5000L);

                    long started = System.nanoTime();
                    try {
                        template.send("kafka-optional-broker-test", "k", "v").get(30, TimeUnit.SECONDS);
                    } catch (Exception expected) {
                        // no broker: the failure itself is the expected outcome, how long it took is what is checked
                    }
                    assertThat(Duration.ofNanos(System.nanoTime() - started))
                            .as("blocked for max.block.ms (5 s), not the 60 s default").isLessThan(Duration.ofSeconds(20));
                });
    }

    @Test
    @DisplayName("outbox relay with a blank broker leaves every row PENDING instead of burning its attempts")
    void outboxPoller_withBlankBroker_doesNotTouchTheOutbox() {
        BookingEventOutboxRepository outbox = mock(BookingEventOutboxRepository.class);
        BookingOutboxPoller poller = new BookingOutboxPoller(outbox, mock(KafkaTemplate.class));
        ReflectionTestUtils.setField(poller, "bootstrapServers", "");

        poller.pollAndPublishPendingEvents();
        poller.pollAndPublishPendingEvents();

        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("outbox relay with a broker configured polls as before")
    void outboxPoller_withBroker_polls() {
        BookingEventOutboxRepository outbox = mock(BookingEventOutboxRepository.class);
        BookingOutboxPoller poller = new BookingOutboxPoller(outbox, mock(KafkaTemplate.class));
        ReflectionTestUtils.setField(poller, "bootstrapServers", "broker.invalid:9092");

        poller.pollAndPublishPendingEvents();

        verify(outbox).findPendingEventsForProcessing(org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.anyInt());
    }
}
