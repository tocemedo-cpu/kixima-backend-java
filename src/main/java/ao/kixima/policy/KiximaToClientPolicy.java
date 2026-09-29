package ao.kixima.policy;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.company.PolicyStatus;
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
 * Espelha o modelo Prisma `KiximaToClientPolicy` (schema.prisma:1496-1519,
 * tabela `kixima_to_client_policies`) — apólice de seguro KIXIMA→Cliente,
 * emitida pelo Admin do Sistema (área "apólices") após due diligence, por
 * empresa. {@code expiryAlertSentAt} evita reenviar o aviso de expiração
 * (ver {@link PolicyService#enviarAvisosDeExpiracao()}).
 */
@Entity
@Table(name = "kixima_to_client_policies")
public class KiximaToClientPolicy extends AbstractPersistableEntity<String> {

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
    private PolicyStatus status = PolicyStatus.APROVADA;

    @Column(name = "issued_by_id", nullable = false)
    private String issuedById;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "expiry_alert_sent_at")
    private Instant expiryAlertSentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KiximaToClientPolicy() {
        // JPA
    }

    public KiximaToClientPolicy(String id, String companyId, String policyNumber, String insurer,
                                 BigDecimal coverageAmount, String currency, String issuedById,
                                 Instant validFrom, Instant validUntil, Instant createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.policyNumber = policyNumber;
        this.insurer = insurer;
        this.coverageAmount = coverageAmount;
        this.currency = currency == null || currency.isBlank() ? "AOA" : currency;
        this.issuedById = issuedById;
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

    public String getIssuedById() {
        return issuedById;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public Instant getExpiryAlertSentAt() {
        return expiryAlertSentAt;
    }

    public void setExpiryAlertSentAt(Instant expiryAlertSentAt) {
        this.expiryAlertSentAt = expiryAlertSentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
