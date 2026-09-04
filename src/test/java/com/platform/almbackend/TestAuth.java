package com.platform.almbackend;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

public final class TestAuth {
    private TestAuth() {}

    public static RequestPostProcessor asAdmin(long id, String name) {
        return jwt().jwt(jwt -> jwt.subject(String.valueOf(id))
                        .claim("name", name)
                        .claim("email", name.toLowerCase() + "@test.com")
                        .claim("roles", List.of("USER", "ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** email 클레임이 없는 토큰 — 주소를 모르는 사용자 */
    public static RequestPostProcessor asUserWithoutEmail(long id, String name) {
        return jwt().jwt(jwt -> jwt.subject(String.valueOf(id))
                        .claim("name", name)
                        .claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    public static RequestPostProcessor asUser(long id, String name) {
        return jwt().jwt(jwt -> jwt.subject(String.valueOf(id))
                        .claim("name", name)
                        .claim("email", name.toLowerCase() + "@test.com")
                        .claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}

