package com.homeease.backend.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${homeease.kafka.topics.booking-events:firstdoor.booking.events.v1}")
    private String bookingEventsTopic;

    @Value("${homeease.kafka.topics.booking-events-dlt:firstdoor.booking.events.DLT}")
    private String bookingEventsDltTopic;

    @Value("${homeease.kafka.topics.partitions:3}")
    private int topicPartitions;

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

    // --- Topic Declarations ---

    @Bean
    public NewTopic bookingEventsTopic() {
        return TopicBuilder.name(bookingEventsTopic)
                .partitions(topicPartitions)
                .replicas(topicReplicationFactor)
                .build();
    }

    @Bean
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
        factory.setConcurrency(topicPartitions);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        // Dead letter publishing recoverer routes failed messages to DLT
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(bookingEventsDltTopic, record.partition())
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
