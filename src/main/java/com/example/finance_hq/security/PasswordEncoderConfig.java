11package com.example.finance_hq.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// Kept out of SecurityConfig: AuthService (needed by JwtAuthenticationFilter, needed by
// SecurityConfig's own filter chain) depends on PasswordEncoder, so defining it inside
// SecurityConfig reintroduces the circular dependency fixed in 278be07.
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
