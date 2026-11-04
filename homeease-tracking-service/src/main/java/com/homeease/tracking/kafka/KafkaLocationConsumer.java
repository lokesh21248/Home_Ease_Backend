package com.homeease.tracking.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.dto.LocationPayload;
import com.homeease.tracking.service.LiveTrackingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "homeease.tracking.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class KafkaLocationConsumer {

    private static final Logger logger = LoggerFactory.getLogger(KafkaLocationConsumer.class);

    private final LiveTrackingService trackingService;
    private final ObjectMapper objectMapper;

    public KafkaLocationConsumer(LiveTrackingService trackingService, ObjectMapper objectMapper) {
        this.trackingService = trackingService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${homeease.tracking.kafka.topic:homeease.tracking.locations.v1}",
            groupId = "${spring.kafka.consumer.group-id:homeease-tracking-ingestion-group}"
    )
    public void consumeLocationEvent(ConsumerRecord<String, String> record) {
        String bookingId = record.key();
        String jsonPayload = record.value();

        try {
            LocationPayload payload = objectMapper.readValue(jsonPayload, LocationPayload.class);
            trackingService.processLocationUpdateDirect(bookingId, payload, payload.getEffectiveId());
            logger.debug("[kafka-consumer] Processed location event for booking {} from partition {} at offset {}",
                    bookingId, record.partition(), record.offset());
        } catch (Exception e) {
            logger.error("[kafka-consumer] Error processing location event for booking {}: {}", bookingId, e.getMessage());
        }
    }
}
