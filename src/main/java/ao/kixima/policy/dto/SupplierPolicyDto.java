package ao.kixima.policy.dto;

import ao.kixima.company.PolicyStatus;
import ao.kixima.company.SupplierToKiximaPolicy;

import java.math.BigDecimal;
import java.time.Instant;

/** Espelha o corpo devolvido para uma `SupplierToKiximaPolicy` (Fornecedor→KIXIMA). */
public record SupplierPolicyDto(String id, String companyId, String policyNumber, String insurer,
                                 BigDecimal coverageAmount, String currency, PolicyStatus status,
                                 Instant validFrom, Instant validUntil, String documentUrl,
                                 Instant createdAt, Instant updatedAt) {

    public static SupplierPolicyDto from(SupplierToKiximaPolicy p) {
        return new SupplierPolicyDto(p.getId(), p.getCompanyId(), p.getPolicyNumber(), p.getInsurer(),
                p.getCoverageAmount(), p.getCurrency(), p.getStatus(), p.getValidFrom(), p.getValidUntil(),
                p.getDocumentUrl(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
