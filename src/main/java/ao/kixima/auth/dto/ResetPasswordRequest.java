package ao.kixima.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Campo `password` (não `newPassword`) — espelha resetPasswordSchema/authController.resetPassword. */
public record ResetPasswordRequest(@NotBlank String token, @NotBlank String password) {
}
