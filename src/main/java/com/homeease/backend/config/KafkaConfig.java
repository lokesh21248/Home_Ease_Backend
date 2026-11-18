package com.homeease.backend.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConfig {

    private static final Logger logger = LoggerFactory.getLogger(KafkaConfig.class);

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${homeease.kafka.topics.booking-events:firstdoor.booking.events.v1}")
    private String bookingEventsTopic;

    @Value("${homeease.kafka.topics.booking-events-dlt:firstdoor.booking.events.DLT}")
    private String bookingEventsDltTopic;

    @Value("${homeease.kafka.topics.partitions:3}")
    private int topicPartitions;

    @Value("${spring.kafka.listener.concurrency:3}")
    private int listenerConcurrency;

    @Value("${homeease.kafka.topics.replication-factor:1}")
    private short topicReplicationFactor;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Value("${spring.kafka.properties.security.protocol:PLAINTEXT}")
    private String securityProtocol;

    @Value("${spring.kafka.properties.sasl.mechanism:PLAIN}")
    private String saslMechanism;

    @Value("${spring.kafka.properties.sasl.jaas.config:}")
    private String saslJaasConfig;

    /**
     * Whether the {@code @KafkaListener} containers start with the application. Spring Boot's own property, but it is
     * only applied by Boot's auto-configured listener container factory, which backs off because this class defines its
     * own {@code kafkaListenerContainerFactory}. It therefore has to be applied here, or it is silently ignored and the
     * consumers always start (and a blank or unreachable broker then stops the application from starting at all).
     */
    @Value("${spring.kafka.listener.auto-startup:false}")
    private boolean listenerAutoStartup;

    /**
     * How long {@code KafkaTemplate.send} may block waiting for broker metadata. The Kafka default is 60 s, which
     * would freeze the single scheduler thread (shared with every other {@code @Scheduled} job) for a minute per
     * outbox event whenever the broker is down.
     */
    @Value("${homeease.kafka.producer.max-block-ms:5000}")
    private long producerMaxBlockMs;

    /** A blank {@code KAFKA_BOOTSTRAP_SERVERS} means "no broker is provisioned": Kafka stays off instead of failing. */
    private boolean brokerConfigured() {
        return bootstrapServers != null && !bootstrapServers.isBlank();
    }

    // --- Topic Declarations ---

    @Bean
    @ConditionalOnProperty(name = "homeease.kafka.admin.auto-create", havingValue = "true", matchIfMissing = false)
    public NewTopic bookingEventsTopic() {
        return TopicBuilder.name(bookingEventsTopic)
                .partitions(topicPartitions)
                .replicas(topicReplicationFactor)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "homeease.kafka.admin.auto-create", havingValue = "true", matchIfMissing = false)
    public NewTopic bookingEventsDltTopic() {
        return TopicBuilder.name(bookingEventsDltTopic)
                .partitions(topicPartitions)
                .replicas(topicReplicationFactor)
                .build();
    }

    // --- Producer Configuration ---

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        configProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, producerMaxBlockMs);

        applySecurityConfig(configProps);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // --- Consumer Configuration ---

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        applySecurityConfig(props);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(listenerConcurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        // Honour spring.kafka.listener.auto-startup (see listenerAutoStartup) and never start consumers without a broker.
        boolean autoStartup = listenerAutoStartup && brokerConfigured();
        factory.setAutoStartup(autoStartup);
        logger.info("Kafka listener containers auto-startup={} (spring.kafka.listener.auto-startup={}, broker configured={})",
                autoStartup, listenerAutoStartup, brokerConfigured());

        // Dead letter publishing recoverer routes failed messages to DLT. The partition is left to Kafka (-1): using the
        // source record's partition number fails, and the failed record is then retried forever instead of parked, as
        // soon as the DLT topic has fewer partitions than the source topic.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(bookingEventsDltTopic, -1)
        );

        // DefaultErrorHandler with 3 retries, 1-second backoff
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }

    private void applySecurityConfig(Map<String, Object> props) {
        if (securityProtocol != null && !securityProtocol.isBlank() && !"PLAINTEXT".equalsIgnoreCase(securityProtocol)) {
            props.put("security.protocol", securityProtocol);
            if (saslMechanism != null && !saslMechanism.isBlank()) {
                props.put("sasl.mechanism", saslMechanism);
            }
            if (saslJaasConfig != null && !saslJaasConfig.isBlank()) {
                props.put("sasl.jaas.config", saslJaasConfig);
            }
        }
    }
}
