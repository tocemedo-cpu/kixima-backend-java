package ao.kixima.policy.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Espelha `supplierPolicySchema`/`clientPolicySchema` (idênticos) — dados comuns às duas apólices. */
public record PolicyRequest(String policyNumber, String insurer, BigDecimal coverageAmount, String currency,
                             Instant validFrom, Instant validUntil) {
}
