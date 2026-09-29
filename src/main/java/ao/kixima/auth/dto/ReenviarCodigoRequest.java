package ao.kixima.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ReenviarCodigoRequest(@NotBlank String challenge) {
}
