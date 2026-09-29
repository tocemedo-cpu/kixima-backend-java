package ao.kixima.catalog.dto;

import java.util.List;

/** Espelha o retorno de catalogService.listSupplierDocuments — para o módulo de Documentação. */
public record SupplierDocumentsResponse(List<SupplierDocumentDto> productDocs, List<CompanyDocumentDto> companyDocs) {
}
