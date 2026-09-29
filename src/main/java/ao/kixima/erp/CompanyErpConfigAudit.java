package ao.kixima.erp;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Espelha o modelo Prisma `CompanyErpConfigAudit` (schema.prisma:468-482,
 * tabela `company_erp_config_audits`) — trilho de auditoria próprio da
 * configuração ERP (SET/TEST), à parte do {@code AuditLog} genérico.
 */
@Entity
@Table(name = "company_erp_config_audits")
public class CompanyErpConfigAudit extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(nullable = false)
    private String action;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "from_erp")
    private CompanyErpSystem fromErp;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "to_erp")
    private CompanyErpSystem toErp;

    @Column(name = "actor_user_id")
    private String actorUserId;

    @Column(name = "actor_name")
    private String actorName;

    private String result;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CompanyErpConfigAudit() {
        // JPA
    }

    public CompanyErpConfigAudit(String id, String companyId, String action, CompanyErpSystem fromErp,
                                  CompanyErpSystem toErp, String actorUserId, String actorName, String result,
                                  Instant createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.action = action;
        this.fromErp = fromErp;
        this.toErp = toErp;
        this.actorUserId = actorUserId;
        this.actorName = actorName;
        this.result = result;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getAction() {
        return action;
    }

    public CompanyErpSystem getFromErp() {
        return fromErp;
    }

    public CompanyErpSystem getToErp() {
        return toErp;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public String getActorName() {
        return actorName;
    }

    public String getResult() {
        return result;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
