package ao.kixima.audit;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Espelha o modelo Prisma `AuditLog` (schema.prisma:1838-1861, tabela
 * `audit_logs`). Trilho APPEND-ONLY — nenhum caminho de update/delete,
 * mesmo princípio do Node. Escrita: {@link AuditService#recordSafe}.
 * Leitura paginada/filtrável para o Admin do Sistema:
 * {@link AuditService#list}.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    @Column(name = "entity_ref")
    private String entityRef;

    @Column(name = "actor_id")
    private String actorId;

    @Column(name = "actor_name")
    private String actorName;

    @Column(name = "actor_role")
    private String actorRole;

    @Column(name = "company_id")
    private String companyId;

    private String ip;

    @JdbcTypeCode(SqlTypes.JSON)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLog() {
        // JPA
    }

    public AuditLog(String id, String action, String entityType, String entityId, String entityRef,
                     String actorId, String actorName, String actorRole, String companyId,
                     String ip, String detailJson, Instant createdAt) {
        this.id = id;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.entityRef = entityRef;
        this.actorId = actorId;
        this.actorName = actorName;
        this.actorRole = actorRole;
        this.companyId = companyId;
        this.ip = ip;
        this.detail = detailJson;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public String getEntityRef() {
        return entityRef;
    }

    public String getActorId() {
        return actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getIp() {
        return ip;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
