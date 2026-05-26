package com.example.finance_hq.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank
        @Size(min = 8)
        @Pattern(
                regexp = "(?=.*[A-Z])(?=.*[0-9])(?=.*[@#$%^&+=!?]).*",
                message = "must contain uppercase, digit, and special character"
        )
        String password
) {}
