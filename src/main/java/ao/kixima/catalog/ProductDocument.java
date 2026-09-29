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

/** Espelha o modelo Prisma `ProductDocument` (schema.prisma:859-869, tabela `product_documents`). */
@Entity
@Table(name = "product_documents")
public class ProductDocument extends AbstractPersistableEntity<String> {

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
    private ProductDocType type;

    @Column(name = "file_url", nullable = false)
    private String fileUrl;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProductDocument() {
        // JPA
    }

    public ProductDocument(String id, String productId, ProductDocType type, String fileUrl, String originalName, Instant createdAt) {
        this.id = id;
        this.productId = productId;
        this.type = type;
        this.fileUrl = fileUrl;
        this.originalName = originalName;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public ProductDocType getType() {
        return type;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public String getOriginalName() {
        return originalName;
    }

    public String getProductId() {
        return productId;
    }

    public Product getProduct() {
        return product;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
