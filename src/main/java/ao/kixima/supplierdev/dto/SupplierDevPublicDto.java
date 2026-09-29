package ao.kixima.supplierdev.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Espelha PUBLIC_SELECT (supplierDevService.js) — consulta pública do estado por referência. */
public record SupplierDevPublicDto(String id, String reference, String companyName, String track, String status,
                                    Instant createdAt, BigDecimal accessFeeUsd, String feeStatus, Instant feePaidAt,
                                    BigDecimal programFeeUsd, boolean customPricing) {
}
