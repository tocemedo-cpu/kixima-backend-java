package ao.kixima.catalog;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/** Espelha o modelo Prisma `ProductImage` (schema.prisma:836-846, tabela `product_images`). */
@Entity
@Table(name = "product_images")
public class ProductImage extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private Product product;

    @Column(nullable = false)
    private String url;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProductImage() {
        // JPA
    }

    public ProductImage(String id, String productId, String url, boolean primary, int sortOrder, Instant createdAt) {
        this.id = id;
        this.productId = productId;
        this.url = url;
        this.primary = primary;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
    }

    public String getProductId() {
        return productId;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public String getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }

    public boolean isPrimary() {
        return primary;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
