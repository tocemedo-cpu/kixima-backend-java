package ao.kixima.marketplace.dto;

import java.time.Instant;

public record SavedSearchDto(String id, String userId, String label, String query, Instant createdAt) {
}
