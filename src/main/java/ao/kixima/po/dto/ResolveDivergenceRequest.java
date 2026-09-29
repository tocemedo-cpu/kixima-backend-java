package ao.kixima.po.dto;

import jakarta.validation.constraints.NotBlank;

public record ResolveDivergenceRequest(@NotBlank String outcome, String notes) {
}
