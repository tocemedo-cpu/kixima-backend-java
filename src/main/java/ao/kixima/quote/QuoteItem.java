package ao.kixima.quote;

import ao.kixima.catalog.Product;
import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Espelha `model QuoteItem` (tabela quote_items). */
@Entity
@Table(name = "quote_items")
public class QuoteItem extends AbstractPersistableEntity<String> {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(name = "quote_request_id", nullable = false)
    private String quoteRequestId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quote_request_id", insertable = false, updatable = false)
    private QuoteRequest quoteRequest;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity = 1;

    protected QuoteItem() {
    }

    public QuoteItem(String id, String quoteRequestId, String productId, int quantity) {
        this.id = id;
        this.quoteRequestId = quoteRequestId;
        this.productId = productId;
        this.quantity = quantity;
    }

    @Override
    public String getId() {
        return id;
    }

    public String getQuoteRequestId() {
        return quoteRequestId;
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
}
