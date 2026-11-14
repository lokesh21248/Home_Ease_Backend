package com.homeease.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class NearbyWorkersProtectionTest {

    // ------------------------------------------------------------------------------- limiter

    @Test
    void limiter_allows_up_to_the_limit_then_rejects_with_a_retry_after_and_resets_next_window() {
        AtomicLong clock = new AtomicLong(1_000_000);
        FixedWindowRateLimiter l = new FixedWindowRateLimiter(3, 60_000, 100, clock::get);
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        FixedWindowRateLimiter.Decision denied = l.tryAcquire("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);

        assertThat(l.tryAcquire("b").allowed()).as("other clients are independent").isTrue();

        clock.addAndGet(60_000);
        assertThat(l.tryAcquire("a").allowed()).as("a new window starts").isTrue();
    }

    @Test
    void limiter_is_off_at_zero_and_memory_is_bounded() {
        FixedWindowRateLimiter off = new FixedWindowRateLimiter(0, 60_000);
        for (int i = 0; i < 1000; i++) {
            assertThat(off.tryAcquire("x").allowed()).isTrue();
        }
        AtomicLong clock = new AtomicLong(0);
        FixedWindowRateLimiter small = new FixedWindowRateLimiter(5, 60_000, 50, clock::get);
        for (int i = 0; i < 5000; i++) {
            small.tryAcquire("client-" + i);
        }
        assertThat(small.trackedKeys()).as("never grows past the cap").isLessThanOrEqualTo(50);
    }

    // ------------------------------------------------------------------------------- filter

    private MockHttpServletResponse call(NearbyWorkersRateLimitFilter f, String method, String uri,
                                         String cf, String realIp, String remote) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        if (cf != null) req.addHeader("CF-Connecting-IP", cf);
        if (realIp != null) req.addHeader("X-Real-IP", realIp);
        req.setRemoteAddr(remote);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void the_filter_limits_one_client_but_not_others_and_answers_429_with_retry_after_and_no_internals() throws Exception {
        NearbyWorkersRateLimitFilter f = new NearbyWorkersRateLimitFilter(2, 1000);
        String uri = "/api/v1/bookings/nearby-workers";
        assertThat(call(f, "GET", uri, "1.1.1.1", null, "10.0.0.1").getStatus()).isEqualTo(200);
        assertThat(call(f, "GET", uri, "1.1.1.1", null, "10.0.0.1").getStatus()).isEqualTo(200);
        MockHttpServletResponse limited = call(f, "GET", uri, "1.1.1.1", null, "10.0.0.1");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotBlank();
        assertThat(limited.getContentAsString()).contains("TOO_MANY_REQUESTS").doesNotContain("1.1.1.1");
        assertThat(call(f, "GET", uri, "2.2.2.2", null, "10.0.0.1").getStatus()).as("another client").isEqualTo(200);
    }

    @Test
    void the_global_cap_holds_even_when_every_request_claims_a_different_identity() throws Exception {
        NearbyWorkersRateLimitFilter f = new NearbyWorkersRateLimitFilter(100, 3);
        int ok = 0;
        int limited = 0;
        for (int i = 0; i < 10; i++) { // an attacker rotating forged CF-Connecting-IP values
            int status = call(f, "GET", "/api/bookings/nearby-workers", "9.9.9." + i, null, "10.0.0.9").getStatus();
            if (status == 200) ok++; else if (status == 429) limited++;
        }
        assertThat(ok).isEqualTo(3);
        assertThat(limited).isEqualTo(7);
    }

    @Test
    void only_the_nearby_workers_get_endpoint_is_filtered() throws Exception {
        NearbyWorkersRateLimitFilter f = new NearbyWorkersRateLimitFilter(1, 1);
        for (int i = 0; i < 5; i++) {
            assertThat(call(f, "GET", "/api/v1/bookings/available-slots", "1.1.1.1", null, "10.0.0.1").getStatus()).isEqualTo(200);
            assertThat(call(f, "POST", "/api/v1/bookings/nearby-workers", "1.1.1.1", null, "10.0.0.1").getStatus()).isEqualTo(200);
            assertThat(call(f, "GET", "/api/v1/bookings/instant", "1.1.1.1", null, "10.0.0.1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void client_identity_prefers_cloudflare_then_real_ip_then_the_socket_and_zero_disables_the_filter() throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/x");
        r.setRemoteAddr("10.0.0.5");
        assertThat(NearbyWorkersRateLimitFilter.clientKey(r)).isEqualTo("10.0.0.5");
        r.addHeader("X-Real-IP", "5.5.5.5");
        assertThat(NearbyWorkersRateLimitFilter.clientKey(r)).isEqualTo("5.5.5.5");
        r.addHeader("CF-Connecting-IP", "7.7.7.7");
        assertThat(NearbyWorkersRateLimitFilter.clientKey(r)).isEqualTo("7.7.7.7");

        NearbyWorkersRateLimitFilter off = new NearbyWorkersRateLimitFilter(0, 0);
        for (int i = 0; i < 50; i++) {
            assertThat(call(off, "GET", "/api/v1/bookings/nearby-workers", "1.1.1.1", null, "10.0.0.1").getStatus()).isEqualTo(200);
        }
    }
}
