package com.ai.analyzer.scan.pscan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HostRateLimiter - L4 Host 限流")
class HostRateLimiterTest {

    @Test
    @DisplayName("should_allow_requests_within_limit")
    void should_allow_requests_within_limit() {
        HostRateLimiter limiter = new HostRateLimiter(3);

        assertThat(limiter.tryAcquire("a.com")).isTrue();
        assertThat(limiter.tryAcquire("a.com")).isTrue();
        assertThat(limiter.tryAcquire("a.com")).isTrue();
    }

    @Test
    @DisplayName("should_reject_when_over_limit")
    void should_reject_when_over_limit() {
        HostRateLimiter limiter = new HostRateLimiter(2);

        assertThat(limiter.tryAcquire("a.com")).isTrue();
        assertThat(limiter.tryAcquire("a.com")).isTrue();
        assertThat(limiter.tryAcquire("a.com")).isFalse();
    }

    @Test
    @DisplayName("should_track_hosts_independently")
    void should_track_hosts_independently() {
        HostRateLimiter limiter = new HostRateLimiter(1);

        assertThat(limiter.tryAcquire("a.com")).isTrue();
        assertThat(limiter.tryAcquire("b.com")).isTrue();
        assertThat(limiter.tryAcquire("a.com")).isFalse();
        assertThat(limiter.tryAcquire("b.com")).isFalse();
    }

    @Test
    @DisplayName("should_recover_after_window_elapses")
    void should_recover_after_window_elapses() throws Exception {
        HostRateLimiter limiter = new HostRateLimiter(1);

        assertThat(limiter.tryAcquire("a.com")).isTrue();
        assertThat(limiter.tryAcquire("a.com")).isFalse();

        Thread.sleep(1100);
        assertThat(limiter.tryAcquire("a.com")).isTrue();
    }

    @Test
    @DisplayName("should_not_limit_null_or_empty_host")
    void should_not_limit_null_or_empty_host() {
        HostRateLimiter limiter = new HostRateLimiter(1);

        assertThat(limiter.tryAcquire(null)).isTrue();
        assertThat(limiter.tryAcquire("")).isTrue();
        assertThat(limiter.tryAcquire(null)).isTrue();
    }

    @Test
    @DisplayName("should_reset_windows")
    void should_reset_windows() {
        HostRateLimiter limiter = new HostRateLimiter(1);

        limiter.tryAcquire("a.com");
        limiter.tryAcquire("a.com"); // blocked
        limiter.reset();

        assertThat(limiter.tryAcquire("a.com")).isTrue();
    }

    @Test
    @DisplayName("should_enforce_minimum_limit_of_one")
    void should_enforce_minimum_limit_of_one() {
        HostRateLimiter limiter = new HostRateLimiter(0);
        assertThat(limiter.maxPerSecond()).isEqualTo(1);
    }
}
