package com.homeease.tracking.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.service.LiveTrackingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class KafkaTrackingPipelineTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private LiveTrackingService trackingService;

    private ObjectMapper objectMapper = new ObjectMapper();
    private KafkaLocationProducer producer;
    private KafkaLocationConsumer consumer;

    @BeforeEach
    void setUp() {
        producer = new KafkaLocationProducer(kafkaTemplate, objectMapper);
        ReflectionTestUtils.setField(producer, "trackingTopic", "homeease.tracking.locations.v1");
        ReflectionTestUtils.setField(producer, "kafkaEnabled", true);

        consumer = new KafkaLocationConsumer(trackingService, objectMapper);
    }

    @Test
    @DisplayName("Kafka Producer: Publishes GPS update partitioned by bookingId")
    void testProducerPublishesWithBookingKey() {
        LocationPayload payload = LocationPayload.builder()
                .bookingId("BK-1001")
                .lat(17.4485)
                .lng(78.3745)
                .bearing(90.0)
                .speed(25.0)
                .accuracy(5.0)
                .build();

        RecordMetadata metadata = new RecordMetadata(new TopicPartition("homeease.tracking.locations.v1", 2), 0, 0, 0L, 0, 0);
        SendResult<String, String> sendResult = new SendResult<>(null, metadata);
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(sendResult);

        when(kafkaTemplate.send(eq("homeease.tracking.locations.v1"), eq("BK-1001"), anyString()))
                .thenReturn(future);

        boolean published = producer.publishLocationEvent("BK-1001", payload);

        assertTrue(published);
        verify(kafkaTemplate).send(eq("homeease.tracking.locations.v1"), eq("BK-1001"), contains("17.4485"));
    }

    @Test
    @DisplayName("Kafka Consumer: Consumes GPS event and processes through LiveTrackingService")
    void testConsumerProcessesLocationEvent() throws Exception {
        LocationPayload payload = LocationPayload.builder()
                .bookingId("BK-2002")
                .lat(17.4500)
                .lng(78.3760)
                .bearing(180.0)
                .speed(30.0)
                .workerId("worker-uuid-123")
                .build();

        String json = objectMapper.writeValueAsString(payload);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("homeease.tracking.locations.v1", 1, 100L, "BK-2002", json);

        consumer.consumeLocationEvent(record);

        verify(trackingService).processLocationUpdateDirect(eq("BK-2002"), any(LocationPayload.class), any());
    }
}
