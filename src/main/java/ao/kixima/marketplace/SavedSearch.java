package ao.kixima.marketplace;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Espelha o modelo Prisma `SavedSearch` (schema.prisma:582-592, tabela
 * `saved_searches`) — pesquisa de marketplace guardada pelo utilizador
 * (rótulo + querystring literal, nunca reinterpretada).
 */
@Entity
@Table(name = "saved_searches")
public class SavedSearch extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String query;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SavedSearch() {
        // JPA
    }

    public SavedSearch(String id, String userId, String label, String query, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.label = label;
        this.query = query;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getLabel() {
        return label;
    }

    public String getQuery() {
        return query;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
