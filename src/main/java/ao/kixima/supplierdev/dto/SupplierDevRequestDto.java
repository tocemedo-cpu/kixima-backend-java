package ao.kixima.supplierdev.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Espelha a linha completa de SupplierDevRequest — usado na listagem/gestão do Admin do Sistema. */
public record SupplierDevRequestDto(String id, String reference, String companyId, String companyName, String taxId,
                                     String contactName, String contactEmail, String contactPhone, String province,
                                     String sector, Integer employees, String track, String needs,
                                     BigDecimal accessFeeUsd, String feeStatus, Instant feePaidAt,
                                     BigDecimal programFeeUsd, boolean customPricing, String status,
                                     String adminNotes, String handledById, Instant handledAt,
                                     Instant createdAt, Instant updatedAt) {
}
