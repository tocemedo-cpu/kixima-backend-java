package ao.kixima.catalog.dto;

import java.time.Instant;

/** Espelha um item de `companyDocs` no retorno de catalogService.listSupplierDocuments. */
public record CompanyDocumentDto(String id, String type, String fileUrl, String originalName, Instant createdAt) {
}
