package ao.kixima.catalog.dto;

import java.time.Instant;

/** Espelha um item do retorno de catalogService.listStockMovements — `product` fica aninhado, só com `name`. */
public record StockMovementListItemDto(String id, String type, int quantity, String note, Instant createdAt,
                                        String productId, ProductNameRef product) {

    public record ProductNameRef(String name) {
    }
}
