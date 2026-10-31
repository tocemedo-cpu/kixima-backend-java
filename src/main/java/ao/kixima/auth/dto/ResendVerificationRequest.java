package ao.kixima.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ResendVerificationRequest(@NotBlank String email) {
}
