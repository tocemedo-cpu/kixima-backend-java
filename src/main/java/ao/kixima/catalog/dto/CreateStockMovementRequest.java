package ao.kixima.catalog.dto;

/** Espelha stockMovementSchema (utils/schemas.js). */
public record CreateStockMovementRequest(String productId, String type, Integer quantity, String note) {
}
