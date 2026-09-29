package ao.kixima.company.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** Corpos dos pedidos de companyRoutes.js (schemas zod correspondentes em utils/schemas.js). */
public final class CompanyRequests {

    private CompanyRequests() {
    }

    /**
     * registerCompanySchema — chega por multipart (campos de texto + documentos),
     * por isso é um bean mutável para o {@code @ModelAttribute}; a política de
     * senha, o aceite dos termos e as datas validam-se no serviço.
     */
    public static class Register {
        @NotBlank @Size(min = 2) public String name;
        @NotBlank @Size(min = 3) public String taxId;
        @NotBlank public String type;
        @NotBlank @Email public String contactEmail;
        public String contactPhone;
        public String address;
        @NotBlank @Size(min = 2) public String adminName;
        @NotBlank @Email public String adminEmail;
        @NotBlank public String adminPassword;
        public String policyNumber;
        public String insurer;
        public BigDecimal coverageAmount;
        public String policyCurrency;
        public String policyValidFrom;
        public String policyValidUntil;
        public String employees;
        public String annualRevenueUsd;
        public String plan;
        public String termsAccepted;

        // setters para o binding do @ModelAttribute
        public void setName(String v) { name = v; }
        public void setTaxId(String v) { taxId = v; }
        public void setType(String v) { type = v; }
        public void setContactEmail(String v) { contactEmail = v; }
        public void setContactPhone(String v) { contactPhone = v; }
        public void setAddress(String v) { address = v; }
        public void setAdminName(String v) { adminName = v; }
        public void setAdminEmail(String v) { adminEmail = v; }
        public void setAdminPassword(String v) { adminPassword = v; }
        public void setPolicyNumber(String v) { policyNumber = v; }
        public void setInsurer(String v) { insurer = v; }
        public void setCoverageAmount(BigDecimal v) { coverageAmount = v; }
        public void setPolicyCurrency(String v) { policyCurrency = v; }
        public void setPolicyValidFrom(String v) { policyValidFrom = v; }
        public void setPolicyValidUntil(String v) { policyValidUntil = v; }
        public void setEmployees(String v) { employees = v; }
        public void setAnnualRevenueUsd(String v) { annualRevenueUsd = v; }
        public void setPlan(String v) { plan = v; }
        public void setTermsAccepted(String v) { termsAccepted = v; }
    }

    public record Decide(@NotNull Boolean approve, String rejectionReason) {
    }

    public record BudgetLimitRequest(@NotNull @Positive BigDecimal periodMonthly, String currency) {
        public String moedaOuAoa() {
            return currency == null || currency.isBlank() ? "AOA" : currency;
        }
    }

    /** bankDetailsSchema — todos opcionais; "" é tratado como ausente. */
    public record BankDetails(String bankName, String iban, String swift) {
    }

    /** companyPlanSchema — seatPriceUsd não-negativo e no máximo 100 (o teto valida-se no schema: 422). */
    public record Plan(String size, String plan, @PositiveOrZero @DecimalMax("100") BigDecimal seatPriceUsd,
                       @PositiveOrZero Integer employees, @PositiveOrZero BigDecimal annualRevenueUsd, @Size(max = 500) String planNotes) {
    }

    public record SerieFiscal(@Size(max = 20) @Pattern(regexp = "^[A-Za-z0-9-]*$", message = "Use só letras, números e hífen.") String serieFiscal) {
    }

    public record DataAdesao(Instant dataAdesao) {
    }

    /** createUserSchema — ADMIN_SISTEMA fica DE FORA de propósito (um assessor só entra por convite). */
    public record CreateUser(@NotBlank @Size(min = 2) String name, @NotBlank @Email String email, @NotBlank String password,
                             @NotBlank String role, String companyId, @Positive BigDecimal approvalCap) {
    }
}
