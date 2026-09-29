package ao.kixima.erp;

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

import java.time.Instant;

/**
 * Espelha o modelo Prisma `CompanyErpConfig` (schema.prisma:453-466, tabela
 * `company_erp_configs`) — seleção e credenciais (cifradas AES-256-GCM,
 * ver {@link ErpCryptoService}) do ERP externo de uma empresa. Uma linha
 * por empresa ({@code companyId} único).
 */
@Entity
@Table(name = "company_erp_configs")
public class CompanyErpConfig extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "company_id", nullable = false, unique = true)
    private String companyId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private CompanyErpSystem erp = CompanyErpSystem.MANUAL;

    @Column(name = "config_enc")
    private String configEnc;

    @Column(name = "last_test_at")
    private Instant lastTestAt;

    @Column(name = "last_test_ok")
    private Boolean lastTestOk;

    @Column(name = "last_test_message")
    private String lastTestMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CompanyErpConfig() {
        // JPA
    }

    public CompanyErpConfig(String id, String companyId, CompanyErpSystem erp, String configEnc, Instant createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.erp = erp;
        this.configEnc = configEnc;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public CompanyErpSystem getErp() {
        return erp;
    }

    public void setErp(CompanyErpSystem erp) {
        this.erp = erp;
    }

    public String getConfigEnc() {
        return configEnc;
    }

    public void setConfigEnc(String configEnc) {
        this.configEnc = configEnc;
    }

    public Instant getLastTestAt() {
        return lastTestAt;
    }

    public void setLastTestAt(Instant lastTestAt) {
        this.lastTestAt = lastTestAt;
    }

    public Boolean getLastTestOk() {
        return lastTestOk;
    }

    public void setLastTestOk(Boolean lastTestOk) {
        this.lastTestOk = lastTestOk;
    }

    public String getLastTestMessage() {
        return lastTestMessage;
    }

    public void setLastTestMessage(String lastTestMessage) {
        this.lastTestMessage = lastTestMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
