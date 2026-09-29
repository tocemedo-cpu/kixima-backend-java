package ao.kixima.catalog;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Espelha o modelo Prisma `StockMovement` (schema.prisma:821-833, tabela
 * `stock_movements`) — entrada/saída de inventário que ajusta
 * {@link Product#getStockQuantity()} (ver {@link CatalogService#createStockMovement}).
 */
@Entity
@Table(name = "stock_movements")
public class StockMovement extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private StockMovementType type;

    @Column(nullable = false)
    private int quantity;

    private String note;

    @Column(name = "created_by_id")
    private String createdById;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockMovement() {
        // JPA
    }

    public StockMovement(String id, String productId, StockMovementType type, int quantity, String note,
                          String createdById, Instant createdAt) {
        this.id = id;
        this.productId = productId;
        this.type = type;
        this.quantity = quantity;
        this.note = note;
        this.createdById = createdById;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getProductId() {
        return productId;
    }

    public Product getProduct() {
        return product;
    }

    public StockMovementType getType() {
        return type;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getNote() {
        return note;
    }

    public String getCreatedById() {
        return createdById;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
