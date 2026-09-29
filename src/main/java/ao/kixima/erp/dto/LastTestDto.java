package ao.kixima.erp.dto;

import java.time.Instant;

public record LastTestDto(Instant at, Boolean ok, String message) {
}
