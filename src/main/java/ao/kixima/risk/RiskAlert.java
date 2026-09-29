package ao.kixima.risk;

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
 * Espelha o modelo Prisma `RiskAlert` (schema.prisma, tabela
 * `risk_alerts`) — Trust & Safety do Chat Comercial. {@code signals}/
 * {@code context} são {@code jsonb}, mapeados como texto JSON bruto
 * (mesmo padrão de Invoice.agtErro/agtEstado, M4).
 */
@Entity
@Table(name = "risk_alerts")
public class RiskAlert extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "message_id", unique = true)
    private String messageId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private RiskLevel level;

    @Column(nullable = false)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    private String signals;

    @JdbcTypeCode(SqlTypes.JSON)
    private String context;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private RiskAlertStatus status = RiskAlertStatus.ABERTO;

    @Column(name = "reviewed_by_id")
    private String reviewedById;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    private String decision;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RiskAlert() {
        // JPA
    }

    public RiskAlert(String id, String conversationId, String messageId, RiskLevel level, String reason,
                      String signals, String context, Instant createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.level = level;
        this.reason = reason;
        this.signals = signals;
        this.context = context;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getMessageId() {
        return messageId;
    }

    public RiskLevel getLevel() {
        return level;
    }

    public String getReason() {
        return reason;
    }

    public String getSignals() {
        return signals;
    }

    public String getContext() {
        return context;
    }

    public RiskAlertStatus getStatus() {
        return status;
    }

    public void setStatus(RiskAlertStatus status) {
        this.status = status;
    }

    public String getReviewedById() {
        return reviewedById;
    }

    public void setReviewedById(String reviewedById) {
        this.reviewedById = reviewedById;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
