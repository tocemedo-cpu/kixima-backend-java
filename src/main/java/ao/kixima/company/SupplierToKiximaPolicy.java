package ao.kixima.company;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Espelha o modelo Prisma `SupplierToKiximaPolicy` (schema.prisma:1473-1493,
 * tabela `supplier_to_kixima_policies`) — apólice de seguro
 * Fornecedor→KIXIMA, exigida para credenciar qualquer empresa fornecedora
 * (ver {@link CompanyService#decideCompanyStatus}). Criada no cadastro
 * público ({@link CompanyService#registerCompany}), na submissão pelo
 * fornecedor ({@code ao.kixima.policy.PolicyService}) e na aprovação de um
 * pedido de Supplier Development ({@code ao.kixima.supplierdev.SupplierDevService#approve}).
 */
@Entity
@Table(name = "supplier_to_kixima_policies")
public class SupplierToKiximaPolicy extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(nullable = false)
    private String insurer;

    @Column(name = "coverage_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal coverageAmount;

    @Column(nullable = false)
    private String currency = "AOA";

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private PolicyStatus status = PolicyStatus.SUBMETIDA;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "document_url")
    private String documentUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SupplierToKiximaPolicy() {
        // JPA
    }

    public SupplierToKiximaPolicy(String id, String companyId, String policyNumber, String insurer,
                                   BigDecimal coverageAmount, String currency, Instant validFrom, Instant validUntil,
                                   Instant createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.policyNumber = policyNumber;
        this.insurer = insurer;
        this.coverageAmount = coverageAmount;
        this.currency = currency == null || currency.isBlank() ? "AOA" : currency;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public String getInsurer() {
        return insurer;
    }

    public BigDecimal getCoverageAmount() {
        return coverageAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public PolicyStatus getStatus() {
        return status;
    }

    public void setDocumentUrl(String documentUrl) {
        this.documentUrl = documentUrl;
    }

    public void setStatus(PolicyStatus status) {
        this.status = status;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public String getDocumentUrl() {
        return documentUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
