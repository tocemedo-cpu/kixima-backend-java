package ao.kixima.payment.dto;

import ao.kixima.company.Company;
import ao.kixima.invoice.Invoice;
import ao.kixima.payment.PlatformFee;
import ao.kixima.payment.PlatformFeeStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/** Espelha a linha `PlatformFee` devolvida pelo Node — com `company`/`invoice` quando o `include` os traz. */
public record PlatformFeeDto(String id, String companyId, String invoiceId, int poCount, BigDecimal perPo, BigDecimal perInvoice,
                             BigDecimal amount, String currency, String basis, BigDecimal poValueUsd, BigDecimal fxRate,
                             PlatformFeeStatus status, Instant chargedAt, Instant createdAt,
                             @JsonInclude(JsonInclude.Include.NON_NULL) CompanyRef company,
                             @JsonInclude(JsonInclude.Include.NON_NULL) InvoiceRef invoice) {

    public record CompanyRef(String name, String type) {
    }

    public record InvoiceRef(String reference, BigDecimal amount, String currency) {
    }

    public static PlatformFeeDto de(PlatformFee f, Company company, Invoice invoice) {
        return new PlatformFeeDto(f.getId(), f.getCompanyId(), f.getInvoiceId(), f.getPoCount(), f.getPerPo(), f.getPerInvoice(),
                f.getAmount(), f.getCurrency(), f.getBasis(), f.getPoValueUsd(), f.getFxRate(), f.getStatus(), f.getChargedAt(),
                f.getCreatedAt(),
                company == null ? null : new CompanyRef(company.getName(), company.getType() == null ? null : company.getType().name()),
                invoice == null ? null : new InvoiceRef(invoice.getReference(), invoice.getAmount(), invoice.getCurrency()));
    }
}
