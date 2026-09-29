package ao.kixima.kit;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Espelha o modelo `KitItem` (schema.prisma). */
@Entity
@Table(name = "kit_items")
public class KitItem extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "kit_id", nullable = false)
    private String kitId;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Column(nullable = false)
    private int quantity = 1;

    protected KitItem() {
        // JPA
    }

    public KitItem(String id, String kitId, String productId, int quantity) {
        this.id = id;
        this.kitId = kitId;
        this.productId = productId;
        this.quantity = quantity;
    }

    public String getId() {
        return id;
    }

    public String getKitId() {
        return kitId;
    }

    public String getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }
}
