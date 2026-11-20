package com.homeease.backend.config;

import com.homeease.backend.routing.BookingRouteContextLoader;
import com.homeease.backend.security.JwtService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // ObjectProvider keeps these lazy: the broker config is created very early and must not pull in JPA eagerly.
    private final ObjectProvider<JwtService> jwtService;
    private final ObjectProvider<BookingRouteContextLoader> contextLoader;

    public WebSocketConfig(ObjectProvider<JwtService> jwtService,
                           ObjectProvider<BookingRouteContextLoader> contextLoader) {
        this.jwtService = jwtService;
        this.contextLoader = contextLoader;
    }

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

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Booking-scoped topics (live worker location, booking status) are participant-only.
        registration.interceptors(new BookingTopicSecurityInterceptor(jwtService, contextLoader));
    }
}
