package ao.kixima.kit;

import org.hibernate.annotations.UpdateTimestamp;
import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Espelha o modelo `Kit` (schema.prisma) — pacote de produtos de um fornecedor. */
@Entity
@Table(name = "kits")
public class Kit extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "supplier_id", nullable = false)
    private String supplierId;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Kit() {
        // JPA
    }

    public Kit(String id, String supplierId, String name, String description, Instant agora) {
        this.id = id;
        this.supplierId = supplierId;
        this.name = name;
        this.description = description;
        this.createdAt = agora;
        this.updatedAt = agora;
    }

    public String getId() {
        return id;
    }

    public String getSupplierId() {
        return supplierId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public void desativar() {
        this.active = false;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
