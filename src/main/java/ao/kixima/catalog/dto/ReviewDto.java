package ao.kixima.catalog.dto;

import java.time.Instant;

/** Espelha reviewService.listForProduct — inclui só o nome do autor (`include: { user: { select: { name } } }`). */
public record ReviewDto(String id, String productId, String userId, String authorName, int rating,
                         String comment, Instant createdAt) {
}
