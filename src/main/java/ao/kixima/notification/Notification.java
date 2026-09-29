package ao.kixima.notification;

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
 * Espelha o modelo Prisma `Notification` (schema.prisma, tabela
 * `notifications`) — notificação in-app/email de um evento de negócio (ver
 * NotificationService). Sem relações JPA para `user`/`company`: a listagem
 * (NotificationController) só precisa dos ids, tal como o Node devolve as
 * linhas em bruto sem `include`.
 */
@Entity
@Table(name = "notifications")
public class Notification extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "company_id")
    private String companyId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private NotificationChannel channel = NotificationChannel.IN_APP;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String message;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "related_entity_type")
    private String relatedEntityType;

    @Column(name = "related_entity_id")
    private String relatedEntityId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
        // JPA
    }

    public Notification(String id, String userId, String companyId, NotificationType type, NotificationChannel channel,
                         String title, String message, String relatedEntityType, String relatedEntityId, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.companyId = companyId;
        this.type = type;
        this.channel = channel;
        this.title = title;
        this.message = message;
        this.relatedEntityType = relatedEntityType;
        this.relatedEntityId = relatedEntityId;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getCompanyId() {
        return companyId;
    }

    public NotificationType getType() {
        return type;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public String getRelatedEntityType() {
        return relatedEntityType;
    }

    public String getRelatedEntityId() {
        return relatedEntityId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
