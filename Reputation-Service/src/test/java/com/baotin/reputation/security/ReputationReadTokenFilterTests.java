package com.baotin.reputation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class ReputationReadTokenFilterTests {
    @Test
    void blankConfigurationDisablesAndMalformedConfigurationFailsClosed() throws Exception {
        var request = new MockHttpServletRequest("GET", "/internal/v1/reputation/targets/unused");
        request.addHeader("X-Service-Token", "a".repeat(64));
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        new ReputationReadTokenFilter("").doFilter(request, response, (req, res) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(called).isFalse();
        for (String invalid : java.util.List.of("short", "A".repeat(64), "a".repeat(63), " ")) {
            assertThatThrownBy(() -> new ReputationReadTokenFilter(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Reputation read-key configuration");
        }
    }

    @Test
    void principalHasOnlyReadScopeAndContextIsClearedEvenWhenDownstreamFails() {
        var request = new MockHttpServletRequest("GET", "/internal/v1/reputation/targets/unused");
        request.addHeader("X-Service-Token", "a".repeat(64));
        assertThatThrownBy(() -> new ReputationReadTokenFilter("a".repeat(64))
            .doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                assertThat(authentication.getAuthorities()).extracting(Object::toString)
                    .containsExactly("SCOPE_reputation.read");
                assertThat(authentication.getCredentials()).isNull();
                throw new jakarta.servlet.ServletException("test downstream failure");
            })).isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
