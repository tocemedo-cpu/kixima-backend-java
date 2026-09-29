package ao.kixima.po;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.catalog.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** Espelha o modelo Prisma `PurchaseOrderItem` (schema.prisma:1024-1039, tabela `purchase_order_items`). */
@Entity
@Table(name = "purchase_order_items")
public class PurchaseOrderItem extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "purchase_order_id", nullable = false)
    private String purchaseOrderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    protected PurchaseOrderItem() {
        // JPA
    }

    public PurchaseOrderItem(String id, String purchaseOrderId, String productId, int quantity,
                              BigDecimal unitPrice, BigDecimal lineTotal) {
        this.id = id;
        this.purchaseOrderId = purchaseOrderId;
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.lineTotal = lineTotal;
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

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public String getPurchaseOrderId() {
        return purchaseOrderId;
    }
}
