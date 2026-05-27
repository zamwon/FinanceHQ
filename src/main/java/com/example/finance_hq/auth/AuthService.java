package com.example.finance_hq.auth;

import com.example.finance_hq.auth.dto.LoginRequest;
import com.example.finance_hq.auth.dto.RegisterRequest;
import com.example.finance_hq.auth.dto.TokenResponse;
import com.example.finance_hq.auth.exception.EmailAlreadyExistsException;
import com.example.finance_hq.user.RefreshToken;
import com.example.finance_hq.user.User;
import com.example.finance_hq.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final long accessTokenExpiry;
    private final String dummyHash;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            @Value("${jwt.access-token.expiry}") long accessTokenExpiry) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.accessTokenExpiry = accessTokenExpiry;
        // Precomputed once so unknown-email login still pays the BCrypt cost (constant-time defense).
        this.dummyHash = passwordEncoder.encode("__dummy__");
    }

    public void register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new EmailAlreadyExistsException("Email already registered");
        }
        userRepository.save(new User(req.email(), passwordEncoder.encode(req.password())));
    }

    public TokenResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.email()).orElse(null);
        if (user == null) {
            // Burn the same BCrypt cost as a real verify so attackers can't enumerate emails by timing.
            passwordEncoder.matches(req.password(), dummyHash);
            throw new BadCredentialsException("Invalid credentials");
        }
        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        String accessToken = jwtService.generateAccessToken(user.getEmail());
        RefreshToken refreshToken = refreshTokenService.create(user);
        return new TokenResponse(accessToken, refreshToken.getToken(), accessTokenExpiry);
    }

    @Transactional
    public TokenResponse refresh(String refreshToken) {
        RefreshToken rotated = refreshTokenService.rotate(refreshToken);
        String accessToken = jwtService.generateAccessToken(rotated.getUser().getEmail());
        return new TokenResponse(accessToken, rotated.getToken(), accessTokenExpiry);
    }

    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }
}
