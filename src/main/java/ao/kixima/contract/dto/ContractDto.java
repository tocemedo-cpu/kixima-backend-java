package ao.kixima.contract.dto;

import ao.kixima.company.Company;
import ao.kixima.contract.BillingPeriodicity;
import ao.kixima.contract.Contract;
import ao.kixima.contract.ContractStatus;
import ao.kixima.po.dto.PurchaseOrderDto;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Espelha a linha `Contract` devolvida pelo Node — todos os escalares,
 * {@code null} incluído — com `clientCompany`/`supplierCompany` (listagens)
 * ou `callOffs` (detalhe) conforme o `include` de cada função; a criação
 * devolve só a linha. Só as relações são condicionais.
 */
public record ContractDto(String id, String reference, String clientCompanyId, String supplierCompanyId, List<String> categoriesCovered,
                          BigDecimal totalValue, String currency, BigDecimal usedValue, BillingPeriodicity billingPeriodicity,
                          int paymentTermDays, ContractStatus status, Instant validFrom, Instant validUntil, Instant createdAt,
                          Instant updatedAt,
                          @JsonInclude(JsonInclude.Include.NON_NULL) CompanyRef clientCompany,
                          @JsonInclude(JsonInclude.Include.NON_NULL) CompanyRef supplierCompany,
                          @JsonInclude(JsonInclude.Include.NON_NULL) List<PurchaseOrderDto> callOffs) {

    public record CompanyRef(String id, String name) {
        public static CompanyRef de(Company c) {
            return c == null ? null : new CompanyRef(c.getId(), c.getName());
        }
    }

    public static ContractDto de(Contract c, boolean comEmpresas, List<PurchaseOrderDto> callOffs) {
        return new ContractDto(c.getId(), c.getReference(), c.getClientCompanyId(), c.getSupplierCompanyId(), c.getCategoriesCovered(),
                c.getTotalValue(), c.getCurrency(), c.getUsedValue(), c.getBillingPeriodicity(), c.getPaymentTermDays(), c.getStatus(),
                c.getValidFrom(), c.getValidUntil(), c.getCreatedAt(), c.getUpdatedAt(),
                comEmpresas ? CompanyRef.de(c.getClientCompany()) : null, comEmpresas ? CompanyRef.de(c.getSupplierCompany()) : null,
                callOffs);
    }
}
