package ao.kixima.marketplace;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Espelha o modelo Prisma `Favorite` (schema.prisma:710-721, tabela
 * `favorites`) — coração persistido por utilizador. `@@unique([userId,
 * productId])`, revisto com upsert em {@link FavoriteService#add}.
 */
@Entity
@Table(name = "favorites", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "product_id"}))
public class Favorite extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Favorite() {
        // JPA
    }

    public Favorite(String id, String userId, String productId, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.productId = productId;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getProductId() {
        return productId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
