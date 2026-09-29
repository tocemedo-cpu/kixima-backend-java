package ao.kixima.contract.dto;

import ao.kixima.contract.BillingPeriodicity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Espelha createContractSchema (zod). {@code currency} por omissão "AOA". */
public record CreateContractRequest(@NotBlank String clientCompanyId, @NotBlank String supplierCompanyId,
                                    @NotEmpty List<@NotBlank String> categoriesCovered, @NotNull @Positive BigDecimal totalValue,
                                    String currency, @NotNull BillingPeriodicity billingPeriodicity, @NotNull @Positive Integer paymentTermDays,
                                    @NotNull Instant validFrom, @NotNull Instant validUntil) {

    public String moedaOuAoa() {
        return currency == null || currency.isBlank() ? "AOA" : currency;
    }
}
