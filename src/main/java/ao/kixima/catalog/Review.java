package ao.kixima.catalog;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Espelha o modelo Prisma `Review` (schema.prisma:724-738, tabela
 * `reviews`) — avaliação (1..5 + comentário opcional) de um produto por um
 * comprador. `@@unique([productId, userId])` — um utilizador só tem uma
 * avaliação por produto, revista com upsert (ver {@link ReviewService}).
 */
@Entity
@Table(name = "reviews", uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "user_id"}))
public class Review extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private int rating;

    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Review() {
        // JPA
    }

    public Review(String id, String productId, String userId, int rating, String comment, Instant createdAt) {
        this.id = id;
        this.productId = productId;
        this.userId = userId;
        this.rating = rating;
        this.comment = comment;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getProductId() {
        return productId;
    }

    public String getUserId() {
        return userId;
    }

    public int getRating() {
        return rating;
    }

    public void setRating(int rating) {
        this.rating = rating;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
