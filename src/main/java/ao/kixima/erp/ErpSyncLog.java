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
import java.util.UUID;

/** Espelha `model ErpSyncLog` (tabela erp_sync_logs) — o rasto de cada troca KIXIMA ↔ ERP por PO. */
@Entity
@Table(name = "erp_sync_logs")
public class ErpSyncLog extends AbstractPersistableEntity<String> {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(name = "purchase_order_id", nullable = false)
    private String purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ErpSyncDirection direction;

    /** ex.: approval_requested, approval_decided, payment_confirmed */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ErpSyncStatus status;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ErpSyncLog() {
    }

    public ErpSyncLog(String purchaseOrderId, ErpSyncDirection direction, String eventType, ErpSyncStatus status,
                      String externalId, String errorMessage) {
        this.id = UUID.randomUUID().toString();
        this.purchaseOrderId = purchaseOrderId;
        this.direction = direction;
        this.eventType = eventType;
        this.status = status;
        this.externalId = externalId;
        this.errorMessage = errorMessage;
        this.createdAt = Instant.now();
    }

    @Override
    public String getId() {
        return id;
    }

    public String getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public ErpSyncDirection getDirection() {
        return direction;
    }

    public String getEventType() {
        return eventType;
    }

    public ErpSyncStatus getStatus() {
        return status;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
