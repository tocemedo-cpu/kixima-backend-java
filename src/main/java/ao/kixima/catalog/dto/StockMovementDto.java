package ao.kixima.catalog.dto;

import java.time.Instant;

/** Espelha o retorno de catalogService.createStockMovement (raw Prisma row). */
public record StockMovementDto(String id, String productId, String type, int quantity, String note,
                                String createdById, Instant createdAt) {
}
