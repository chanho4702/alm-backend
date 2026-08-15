package com.platform.almbackend;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

public final class TestAuth {
    private TestAuth() {}

    public static RequestPostProcessor asUser(long id, String name) {
        return jwt().jwt(jwt -> jwt.subject(String.valueOf(id))
                        .claim("name", name)
                        .claim("email", name.toLowerCase() + "@test.com")
                        .claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}

