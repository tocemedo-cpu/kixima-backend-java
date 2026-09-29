package ao.kixima.policy.dto;

import ao.kixima.company.PolicyStatus;
import ao.kixima.policy.KiximaToClientPolicy;

import java.math.BigDecimal;
import java.time.Instant;

/** Espelha o corpo devolvido para uma `KiximaToClientPolicy` (KIXIMA→Cliente). */
public record ClientPolicyDto(String id, String companyId, String policyNumber, String insurer,
                               BigDecimal coverageAmount, String currency, PolicyStatus status, String issuedById,
                               Instant validFrom, Instant validUntil, Instant expiryAlertSentAt,
                               Instant createdAt, Instant updatedAt) {

    public static ClientPolicyDto from(KiximaToClientPolicy p) {
        return new ClientPolicyDto(p.getId(), p.getCompanyId(), p.getPolicyNumber(), p.getInsurer(),
                p.getCoverageAmount(), p.getCurrency(), p.getStatus(), p.getIssuedById(), p.getValidFrom(),
                p.getValidUntil(), p.getExpiryAlertSentAt(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
