package ao.kixima.supplierdev.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Espelha supplierDevApproveSchema. */
public record ApproveSupplierDevRequest(String taxId, PolicyInput policy) {

    public record PolicyInput(String policyNumber, String insurer, BigDecimal coverageAmount, String currency,
                               Instant validFrom, Instant validUntil) {
    }
}
