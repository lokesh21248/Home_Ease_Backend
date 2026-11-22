package com.homeease.backend.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Applies {@code homeease.security.strict-jwt-tracking} to {@link VerifiedCaller} at startup. */
@Component
public class StrictJwtSettings {

    private static final Logger log = LoggerFactory.getLogger(StrictJwtSettings.class);

    @Value("${homeease.security.strict-jwt-tracking:true}")
    private boolean strict = true;

    @PostConstruct
    void apply() {
        VerifiedCaller.setStrict(strict);
        if (!strict) {
            log.warn("STRICT_JWT_TRACKING=false: tracking, arrival and PIN endpoints accept UNVERIFIED identities. "
                    + "Emergency use only.");
        }
    }
}
