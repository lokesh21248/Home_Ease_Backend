package com.homeease.tracking.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.tracking.dto.LocationPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaLocationProducer {

    private static final Logger logger = LoggerFactory.getLogger(KafkaLocationProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${homeease.tracking.kafka.topic:homeease.tracking.locations.v1}")
    private String trackingTopic;

    @Value("${homeease.tracking.kafka.enabled:false}")
    private boolean kafkaEnabled;

    public KafkaLocationProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean isKafkaEnabled() {
        return kafkaEnabled;
    }

    /**
     * Publishes GPS update to Kafka partitioned by bookingId for strict chronological ordering.
     */
    public boolean publishLocationEvent(String bookingId, LocationPayload payload) {
        if (!kafkaEnabled || kafkaTemplate == null) {
            return false;
        }

        try {
            String json = objectMapper.writeValueAsString(payload);
            // Using bookingId as the partition key guarantees all events for a booking are processed in-order
            kafkaTemplate.send(trackingTopic, bookingId, json).whenComplete((result, ex) -> {
                if (ex != null) {
                    logger.warn("[kafka-producer] Failed to send location for booking {}: {}", bookingId, ex.getMessage());
                } else {
                    logger.debug("[kafka-producer] Sent location for booking {} to partition {}",
                            bookingId, result.getRecordMetadata().partition());
                }
            });
            return true;
        } catch (Exception e) {
            logger.warn("[kafka-producer] Serialization error for booking {}: {}", bookingId, e.getMessage());
            return false;
        }
    }
}

