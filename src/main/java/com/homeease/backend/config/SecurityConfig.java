package com.homeease.backend.config;

import com.homeease.backend.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity(debug = true)
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Public auth & registration endpoints
                .requestMatchers("/api/v1/auth/**", "/api/auth/**", "/api/v1/admin/auth/**", "/api/admin/auth/**").permitAll()
                .requestMatchers("/api/v1/users/register", "/api/users/register", "/api/v1/user/register", "/api/user/register").permitAll()
                // Public catalog, services, banners, categories, explore, explore-services, coupons
                .requestMatchers("/api/v1/catalog/**", "/api/catalog/**").permitAll()
                .requestMatchers("/api/v1/services/**", "/api/services/**").permitAll()
                .requestMatchers("/api/v1/sub-services/**", "/api/sub-services/**").permitAll()
                .requestMatchers("/api/v1/banners/**", "/api/banners/**").permitAll()
                .requestMatchers("/api/v1/categories/**", "/api/categories/**").permitAll()
                .requestMatchers("/api/v1/explore/**", "/api/explore/**").permitAll()
                .requestMatchers("/api/v1/explore-services/**", "/api/explore-services/**", "/api/v1/customer/explore-services/**").permitAll()
                .requestMatchers("/api/v1/coupons/**", "/api/coupons/**").permitAll()
                .requestMatchers("/api/v1/service-detail/**", "/api/service-detail/**").permitAll()
                // Slot discovery & holds
                .requestMatchers("/api/v1/slots/**", "/api/slots/**").permitAll()
                .requestMatchers("/api/v1/slot-holds/**", "/api/slot-holds/**").permitAll()
                .requestMatchers("/api/v1/bookings/available-dates/**", "/api/bookings/available-dates/**").permitAll()
                .requestMatchers("/api/v1/bookings/available-slots/**", "/api/bookings/available-slots/**").permitAll()
                .requestMatchers("/api/v1/bookings/time-slots/**", "/api/bookings/time-slots/**").permitAll()
                .requestMatchers("/api/v1/bookings/slots/**", "/api/bookings/slots/**").permitAll()
                .requestMatchers("/api/v1/booking/slots/**", "/api/booking/slots/**").permitAll()
                // Public service areas, geo, app version, health, serviceability
                .requestMatchers("/api/v1/service-areas/**", "/api/service-areas/**").permitAll()
                .requestMatchers("/api/v1/serviceability/**", "/api/serviceability/**", "/serviceability/**").permitAll()
                .requestMatchers("/api/v1/geo/**", "/api/geo/**").permitAll()
                .requestMatchers("/api/v1/app-version/**", "/api/app-version/**").permitAll()
                .requestMatchers("/api/v1/app/**", "/api/app/**").permitAll()
                .requestMatchers("/api/v1/health/**", "/api/health/**", "/health/**").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                // WebSocket STOMP handshake endpoints (AntPathRequestMatcher matches regardless of Spring MVC dispatch)
                .requestMatchers(
                    AntPathRequestMatcher.antMatcher("/ws/**"),
                    AntPathRequestMatcher.antMatcher("/ws"),
                    AntPathRequestMatcher.antMatcher("/worker/stream/**"),
                    AntPathRequestMatcher.antMatcher("/worker/stream"),
                    AntPathRequestMatcher.antMatcher("/worker/**"),
                    AntPathRequestMatcher.antMatcher("/error")
                ).permitAll()
                // Payment webhooks
                .requestMatchers("/api/v1/payments/webhooks/**", "/api/payments/webhooks/**", "/api/v1/payments/webhook/**", "/api/payments/webhook/**").permitAll()
                // Admin endpoints strictly restricted to ADMIN role
                .requestMatchers("/api/v1/admin/**", "/api/admin/**").hasRole("ADMIN")
                // Worker endpoints strictly restricted to WORKER or ADMIN
                .requestMatchers("/api/v1/workers/**", "/api/workers/**").hasAnyRole("WORKER", "ADMIN")
                // All other endpoints require authentication
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
