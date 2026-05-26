package com.example.finance_hq.auth;

import com.example.finance_hq.auth.exception.InvalidRefreshTokenException;
import com.example.finance_hq.user.RefreshToken;
import com.example.finance_hq.user.RefreshTokenRepository;
import com.example.finance_hq.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService refreshTokenService;
    private User user;

    @BeforeEach
    void setUp() {
        refreshTokenService = new RefreshTokenService(refreshTokenRepository, 2592000L);
        user = new User("user@example.com", "hash");
    }

    @Test
    void create_persistsRow() {
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RefreshToken token = refreshTokenService.create(user);

        assertNotNull(token.getToken());
        assertEquals(user, token.getUser());
        assertNotNull(token.getExpiresAt());
        verify(refreshTokenRepository).save(any());
    }

    @Test
    void rotate_returnsNewTokenAndDeletesOld() {
        String oldTokenValue = "old-token";
        RefreshToken existing = new RefreshToken(oldTokenValue, user, LocalDateTime.now().plusDays(30));

        when(refreshTokenRepository.findByToken(oldTokenValue)).thenReturn(Optional.of(existing));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RefreshToken newToken = refreshTokenService.rotate(oldTokenValue);

        verify(refreshTokenRepository).deleteByToken(oldTokenValue);
        assertNotEquals(oldTokenValue, newToken.getToken());
    }

    @Test
    void rotate_onExpiredToken_throwsInvalidRefreshTokenException() {
        String tokenValue = "expired-token";
        RefreshToken expired = new RefreshToken(tokenValue, user, LocalDateTime.now().minusDays(1));

        when(refreshTokenRepository.findByToken(tokenValue)).thenReturn(Optional.of(expired));

        assertThrows(InvalidRefreshTokenException.class, () -> refreshTokenService.rotate(tokenValue));
    }

    @Test
    void revoke_deletesRow() {
        String tokenValue = "some-token";

        refreshTokenService.revoke(tokenValue);

        verify(refreshTokenRepository).deleteByToken(tokenValue);
    }
}
