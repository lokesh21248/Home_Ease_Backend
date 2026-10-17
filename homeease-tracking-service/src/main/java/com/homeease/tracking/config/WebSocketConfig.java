package com.homeease.tracking.config;

import com.homeease.tracking.security.JwtTokenValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.UUID;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketConfig.class);

    private final JwtTokenValidator jwtValidator;

    public WebSocketConfig(JwtTokenValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();

        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null) {
                    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                        String authHeader = accessor.getFirstNativeHeader("Authorization");
                        if (authHeader == null) {
                            authHeader = accessor.getFirstNativeHeader("token");
                        }
                        String userIdHeader = accessor.getFirstNativeHeader("X-User-Id");

                        String principalName = null;
                        if (authHeader != null && !authHeader.isBlank()) {
                            UUID fromJwt = jwtValidator.extractUserId(authHeader);
                            if (fromJwt != null) {
                                principalName = fromJwt.toString();
                            }
                        }
                        if (principalName == null && userIdHeader != null && !userIdHeader.isBlank()) {
                            principalName = userIdHeader.trim();
                        }

                        if (principalName != null) {
                            final String finalName = principalName;
                            accessor.setUser(() -> finalName);
                            logger.info("STOMP WebSocket client authenticated as: {}", finalName);
                        }
                    }
                }
                return message;
            }
        });
    }
}
