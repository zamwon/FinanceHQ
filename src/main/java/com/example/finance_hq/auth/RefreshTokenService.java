package com.example.finance_hq.auth;

import com.example.finance_hq.auth.exception.InvalidRefreshTokenException;
import com.example.finance_hq.user.RefreshToken;
import com.example.finance_hq.user.RefreshTokenRepository;
import com.example.finance_hq.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final long refreshTokenExpiry;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            @Value("${jwt.refresh-token.expiry}") long refreshTokenExpiry) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    public RefreshToken create(User user) {
        RefreshToken token = new RefreshToken(
                UUID.randomUUID().toString(),
                user,
                LocalDateTime.now().plusSeconds(refreshTokenExpiry)
        );
        return refreshTokenRepository.save(token);
    }

    @Transactional
    public RefreshToken rotate(String oldToken) {
        RefreshToken existing = refreshTokenRepository.findByToken(oldToken)
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid or expired refresh token"));

        if (existing.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidRefreshTokenException("Invalid or expired refresh token");
        }

        User user = existing.getUser();
        refreshTokenRepository.deleteByToken(oldToken);
        return create(user);
    }

    public void revoke(String token) {
        refreshTokenRepository.deleteByToken(token);
    }
}
