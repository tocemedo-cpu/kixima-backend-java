package ao.kixima.conversation;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Espelha o modelo Prisma `ConversationMessage` (schema.prisma, tabela `conversation_messages`). */
@Entity
@Table(name = "conversation_messages")
public class ConversationMessage extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "sender_id", nullable = false)
    private String senderId;

    @Column(name = "sender_company_id", nullable = false)
    private String senderCompanyId;

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

    protected ConversationMessage() {
        // JPA
    }

    public ConversationMessage(String id, String conversationId, String senderId, String senderCompanyId, String body,
                                String attachmentUrl, String attachmentName, Instant createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.senderCompanyId = senderCompanyId;
        this.body = body;
        this.attachmentUrl = attachmentUrl;
        this.attachmentName = attachmentName;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getSenderId() {
        return senderId;
    }

    public String getSenderCompanyId() {
        return senderCompanyId;
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
