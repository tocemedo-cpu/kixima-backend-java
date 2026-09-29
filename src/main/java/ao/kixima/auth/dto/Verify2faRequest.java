package ao.kixima.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record Verify2faRequest(@NotBlank String challenge, @NotBlank String code) {
}
