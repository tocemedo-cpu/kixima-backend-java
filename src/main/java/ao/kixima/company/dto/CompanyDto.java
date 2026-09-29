package ao.kixima.company.dto;

import ao.kixima.company.BudgetLimit;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyDocument;
import ao.kixima.policy.dto.ClientPolicyDto;
import ao.kixima.policy.dto.SupplierPolicyDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Espelha a linha `Company` completa devolvida pelo Node (listCompanies /
 * getCompany) — todos os escalares, {@code null} incluído ({@code logoUrl: null},
 * {@code planoValidoAte: null}…) — com `supplierPolicies`/`clientPolicies`/
 * `budgetLimit`/`documents` no detalhe (o `include`; {@code budgetLimit} é
 * relação 1:1, sai a {@code null} quando não há) e `subscricao` na listagem
 * quando pedida ({@code subs.get(c.id) || null}). Java {@code null} → chave
 * ausente; {@code Optional.empty()} → {@code null} explícito.
 */
public record CompanyDto(String id, String name, String taxId, String type, String status, String contactEmail, String contactPhone,
                         String address, boolean verified, String logoUrl, String city, String province, String country,
                         @JsonRawValue String settings, String bankName, String iban, String swift, String serieFiscal,
                         Instant dataAdesaoFacturacaoElectronica, Instant termsAcceptedAt, Integer employees, BigDecimal annualRevenueUsd,
                         String size, String plan, int searchRank, Instant planoValidoAte, BigDecimal seatPriceUsd, String planNotes,
                         Instant createdAt, Instant updatedAt, Instant approvedAt, Instant rejectedAt,
                         @JsonInclude(JsonInclude.Include.NON_NULL) List<SupplierPolicyDto> supplierPolicies,
                         @JsonInclude(JsonInclude.Include.NON_NULL) List<ClientPolicyDto> clientPolicies,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Optional<BudgetLimitDto> budgetLimit,
                         @JsonInclude(JsonInclude.Include.NON_NULL) List<CompanyDocumentDto> documents,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Optional<SubscriptionDto> subscricao) {

    public record CompanyDocumentDto(String id, String companyId, String type, String fileUrl, String originalName, Instant createdAt) {
        public static CompanyDocumentDto de(CompanyDocument d) {
            return new CompanyDocumentDto(d.getId(), d.getCompanyId(), d.getType().name(), d.getFileUrl(), d.getOriginalName(), d.getCreatedAt());
        }
    }

    public record BudgetLimitDto(String id, String companyId, BigDecimal periodMonthly, String currency, Instant updatedAt) {
        public static BudgetLimitDto de(BudgetLimit b) {
            return new BudgetLimitDto(b.getId(), b.getCompanyId(), b.getPeriodMonthly(), b.getCurrency(), b.getUpdatedAt());
        }
    }

    public static CompanyDto de(Company c) {
        return de(c, null, null, null, null, null);
    }

    public static CompanyDto de(Company c, List<SupplierPolicyDto> supplierPolicies, List<ClientPolicyDto> clientPolicies,
                                Optional<BudgetLimitDto> budgetLimit, List<CompanyDocumentDto> documents, Optional<SubscriptionDto> subscricao) {
        return new CompanyDto(c.getId(), c.getName(), c.getTaxId(), c.getType() == null ? null : c.getType().name(),
                c.getStatus() == null ? null : c.getStatus().name(), c.getContactEmail(), c.getContactPhone(), c.getAddress(),
                c.isVerified(), c.getLogoUrl(), c.getCity(), c.getProvince(), c.getCountry(), c.getSettings(), c.getBankName(), c.getIban(),
                c.getSwift(), c.getSerieFiscal(), c.getDataAdesaoFacturacaoElectronica(), c.getTermsAcceptedAt(), c.getEmployees(),
                c.getAnnualRevenueUsd(), c.getSize() == null ? null : c.getSize().name(), c.getPlan() == null ? null : c.getPlan().name(),
                c.getSearchRank(), c.getPlanoValidoAte(), c.getSeatPriceUsd(), c.getPlanNotes(), c.getCreatedAt(), c.getUpdatedAt(),
                c.getApprovedAt(), c.getRejectedAt(), supplierPolicies, clientPolicies, budgetLimit, documents, subscricao);
    }
}
