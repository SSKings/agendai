package com.sskings.agendai.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPathsTest {

    @Test
    @DisplayName("Caminho como o Spring MVC enxerga: %6C vira l e ;parâmetros somem (senão dava para fugir do limite por IP)")
    void normalized() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/%6Cogin;x=1");
        assertThat(RequestPaths.of(req)).isEqualTo("/api/auth/login");
    }
}
