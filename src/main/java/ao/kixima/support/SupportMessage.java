package ao.kixima.support;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.security.PersonaRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Espelha o modelo Prisma `SupportMessage` (schema.prisma, tabela `support_messages`). */
@Entity
@Table(name = "support_messages")
public class SupportMessage extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "ticket_id", nullable = false)
    private String ticketId;

    @Column(name = "author_id", nullable = false)
    private String authorId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "author_role", nullable = false)
    private PersonaRole authorRole;

    @Column(nullable = false)
    private String body;

    @Column(name = "attachment_url")
    private String attachmentUrl;

    @Column(name = "attachment_name")
    private String attachmentName;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SupportMessage() {
        // JPA
    }

    public SupportMessage(String id, String ticketId, String authorId, PersonaRole authorRole, String body,
                           String attachmentUrl, String attachmentName, Instant createdAt) {
        this.id = id;
        this.ticketId = ticketId;
        this.authorId = authorId;
        this.authorRole = authorRole;
        this.body = body;
        this.attachmentUrl = attachmentUrl;
        this.attachmentName = attachmentName;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getAuthorId() {
        return authorId;
    }

    public PersonaRole getAuthorRole() {
        return authorRole;
    }

    public String getBody() {
        return body;
    }

    public String getAttachmentUrl() {
        return attachmentUrl;
    }

    public String getAttachmentName() {
        return attachmentName;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
