package ao.kixima.catalog.dto;

import java.time.Instant;

/** Espelha um item de `productDocs` no retorno de catalogService.listSupplierDocuments. */
public record SupplierDocumentDto(String id, String type, String fileUrl, String originalName, Instant createdAt,
                                   String productId, String productName) {
}
