package com.homeease.tracking.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

/**
 * Redis connection configuration for HomeEase Single-EC2 deployment.
 *
 * <p>Redis co-located on backend EC2 (127.0.0.1:6379).
 * Lettuce connection pool: 4 min-idle, 64 max-active.
 * Socket timeout: 2 seconds (fail fast on Redis latency spike).
 */
@Configuration
public class RedisConfig {

    private static final Logger logger = LoggerFactory.getLogger(RedisConfig.class);

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.timeout:2000ms}")
    private Duration commandTimeout;

    @Value("${spring.data.redis.connect-timeout:1000ms}")
    private Duration connectTimeout;

    /**
     * Optional full connection URL (e.g. rediss://default:&lt;password&gt;@host:6379), populated from the
     * REDIS_URL environment variable by {@link RedisUrlEnvironmentPostProcessor}. When present it
     * overrides host/port/password and the rediss:// scheme enables TLS.
     */
    /** Optional: present in the running application (actuator), absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /** Externally supplied client resources must be shut down by their owner; see {@link #shutdownMetricsResources()}. */
    private ClientResources metricsClientResources;

    @jakarta.annotation.PreDestroy
    void shutdownMetricsResources() {
        if (metricsClientResources != null) {
            metricsClientResources.shutdown(0, 2, java.util.concurrent.TimeUnit.SECONDS);
            metricsClientResources = null;
        }
    }

    @Value("${spring.data.redis.url:}")
    private String redisUrl;

    /** Connection target resolved from either the URL or the legacy host/port/password properties. */
    record RedisTarget(String host, int port, String username, String password, int database, boolean ssl) {}

    /**
     * Parses a redis:// or rediss:// URL the same way Spring Boot's own auto-configuration does
     * (java.net.URI, user-info split on the first ':'). The URL is never echoed in error messages.
     */
    static RedisTarget parseUrl(String url) {
        final URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid REDIS_URL / spring.data.redis.url (syntax error)");
        }
        String scheme = uri.getScheme();
        if (!"redis".equals(scheme) && !"rediss".equals(scheme)) {
            throw new IllegalArgumentException("Invalid REDIS_URL / spring.data.redis.url (scheme must be redis:// or rediss://)");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid REDIS_URL / spring.data.redis.url (missing host)");
        }
        String username = null;
        String password = null;
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int idx = userInfo.indexOf(':');
            if (idx >= 0) {
                username = userInfo.substring(0, idx);
                password = userInfo.substring(idx + 1);
            } else {
                password = userInfo;
            }
        }
        int database = 0;
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            try {
                database = Integer.parseInt(path.substring(1));
            } catch (NumberFormatException ignored) {
                // keep default DB 0
            }
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 6379;
        return new RedisTarget(uri.getHost(), port, username == null || username.isEmpty() ? null : username,
                password == null || password.isEmpty() ? null : password, database, "rediss".equals(scheme));
    }

    private RedisTarget resolveTarget() {
        if (redisUrl != null && !redisUrl.isBlank()) {
            return parseUrl(redisUrl);
        }
        return new RedisTarget(redisHost, redisPort, null,
                redisPassword == null || redisPassword.isBlank() ? null : redisPassword, 0, false);
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisTarget target = resolveTarget();
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(target.host(), target.port());
        serverConfig.setDatabase(target.database());
        if (target.username() != null) {
            serverConfig.setUsername(target.username());
        }
        if (target.password() != null) {
            serverConfig.setPassword(target.password());
        }

        // Connection pool: keeps warm connections ready for sub-millisecond GPS ingestion
        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMinIdle(4);
        poolConfig.setMaxIdle(16);
        poolConfig.setMaxTotal(64);
        poolConfig.setMaxWait(Duration.ofMillis(500));
        poolConfig.setTestOnBorrow(false);
        poolConfig.setTestOnReturn(false);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));

        // Lettuce client options: auto-reconnect + fast failure on timeout
        ClientOptions clientOptions = ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
                .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                .build();

        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder clientConfigBuilder =
                LettucePoolingClientConfiguration.builder()
                        .commandTimeout(commandTimeout)
                        .clientOptions(clientOptions)
                        .poolConfig(poolConfig);
        if (target.ssl()) {
            clientConfigBuilder.useSsl();
        }
        // Count every Redis command per command name (lettuce.command.completion), exactly as Spring Boot does for
        // factories it builds itself. This factory is hand-built, so Boot's integration would not apply to it.
        // In staging this turns the command-usage estimate into a measurement (/actuator/prometheus).
        if (meterRegistry != null) {
            metricsClientResources = DefaultClientResources.builder()
                    .commandLatencyRecorder(new MicrometerCommandLatencyRecorder(meterRegistry, MicrometerOptions.create()))
                    .build();
            clientConfigBuilder.clientResources(metricsClientResources);
        }
        LettuceClientConfiguration clientConfig = clientConfigBuilder.build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(serverConfig, clientConfig);

        // Never log the URL or password - only non-secret connection facts.
        logger.info("Redis connection configured: {}:{} (tls={}, auth={})",
                target.host(), target.port(), target.ssl(), target.password() == null ? "none" : "***");
        return factory;
    }

    @Bean
    public StringRedisTemplate redisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
