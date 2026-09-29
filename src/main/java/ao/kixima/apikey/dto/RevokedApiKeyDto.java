package ao.kixima.apikey.dto;

import java.time.Instant;

public record RevokedApiKeyDto(String id, Instant revogadaEm) {
}
