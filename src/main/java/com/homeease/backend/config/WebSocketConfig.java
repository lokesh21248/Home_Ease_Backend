package com.homeease.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Pure WebSockets for Mobile Apps (React Native / Android / iOS native WebSocket)
        registry.addEndpoint("/ws", "/worker/stream", "/ws/websocket", "/worker/stream/websocket")
                .setAllowedOriginPatterns("*");

        // SockJS fallback for Web Browsers
        registry.addEndpoint("/ws", "/worker/stream")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
