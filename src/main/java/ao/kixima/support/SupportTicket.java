package ao.kixima.support;

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
 * Espelha o modelo Prisma `SupportTicket` (schema.prisma, tabela
 * `support_tickets`) — pedido de suporte do utilizador + o chat que corre
 * por cima (ver {@link SupportMessage}).
 */
@Entity
@Table(name = "support_tickets")
public class SupportTicket extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "company_id")
    private String companyId;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private SupportStatus status = SupportStatus.ABERTO;

    @Column(name = "assigned_to_id")
    private String assignedToId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SupportTicket() {
        // JPA
    }

    public SupportTicket(String id, String reference, String userId, String companyId, String subject,
                          String category, String message, Instant createdAt) {
        this.id = id;
        this.reference = reference;
        this.userId = userId;
        this.companyId = companyId;
        this.subject = subject;
        this.category = category;
        this.message = message;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public String getUserId() {
        return userId;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getSubject() {
        return subject;
    }

    public String getCategory() {
        return category;
    }

    public String getMessage() {
        return message;
    }

    public SupportStatus getStatus() {
        return status;
    }

    public void setStatus(SupportStatus status) {
        this.status = status;
    }

    public String getAssignedToId() {
        return assignedToId;
    }

    public void setAssignedToId(String assignedToId) {
        this.assignedToId = assignedToId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
