package ao.kixima.erp.dto;

import java.time.Instant;

public record ErpTestResultDto(boolean ok, String message, Instant at) {
}
