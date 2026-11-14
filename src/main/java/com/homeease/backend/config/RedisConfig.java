package com.homeease.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    /**
     * {@code @Primary}: Spring Boot also auto-configures {@code stringRedisTemplate}, and a {@code StringRedisTemplate}
     * IS-A {@code RedisTemplate<String,String>}, so every {@code RedisTemplate<String,String>} injection point has TWO
     * candidates. Without a primary, Spring resolves the tie only by matching the parameter name to the bean name, so
     * a constructor parameter not literally called {@code redisTemplate} stops the whole service from starting
     * (see RedisTemplateWiringTest).
     */
    @Bean
    @Primary
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
