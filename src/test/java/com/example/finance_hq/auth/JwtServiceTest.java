package com.example.finance_hq.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private static final String SECRET = "dGVzdHNlY3JldHRlc3RzZWNyZXR0ZXN0c2VjcmV0dGVzdA==";
    private static final String EMAIL = "user@example.com";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 3600L);
    }

    @Test
    void generateToken_subjectIsEmail() {
        String token = jwtService.generateAccessToken(EMAIL);
        assertEquals(EMAIL, jwtService.extractEmail(token));
    }

    @Test
    void isTokenValid_trueForFreshToken() {
        String token = jwtService.generateAccessToken(EMAIL);
        assertTrue(jwtService.isTokenValid(token));
    }

    @Test
    void isTokenValid_falseForExpiredToken() {
        JwtService shortLived = new JwtService(SECRET, -1L);
        String token = shortLived.generateAccessToken(EMAIL);
        assertFalse(jwtService.isTokenValid(token));
    }

    @Test
    void isTokenValid_falseForTamperedToken() {
        String token = jwtService.generateAccessToken(EMAIL);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        assertFalse(jwtService.isTokenValid(tampered));
    }
}
