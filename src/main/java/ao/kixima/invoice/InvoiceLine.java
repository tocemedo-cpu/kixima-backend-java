package ao.kixima.invoice;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Espelha o modelo Prisma `InvoiceLine` (schema.prisma:1198-1233, tabela
 * `invoice_lines`) — cópia imutável das PurchaseOrderItem no momento da
 * emissão, nunca uma referência viva (a fatura não pode mudar se a PO for
 * editada depois). `iecAmount`/`isAmount` sempre 0 e `ivaTaxCode` sempre
 * "NOR" — nenhuma transação real do KIXIMA hoje é isenta/reduzida/sujeita a
 * IEC ou Selo (mesma nota do schema Prisma).
 */
@Entity
@Table(name = "invoice_lines")
public class InvoiceLine extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "invoice_id", nullable = false)
    private String invoiceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", insertable = false, updatable = false)
    private Invoice invoice;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(name = "net_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "iva_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal ivaAmount = BigDecimal.ZERO;

    @Column(name = "iec_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal iecAmount = BigDecimal.ZERO;

    @Column(name = "is_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal isAmount = BigDecimal.ZERO;

    @Column(name = "iva_tax_code", nullable = false)
    private String ivaTaxCode = "NOR";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InvoiceLine() {
        // JPA
    }

    public InvoiceLine(String id, String invoiceId, int lineNumber, String productCode, String description,
                        BigDecimal quantity, BigDecimal unitPrice, BigDecimal netAmount, BigDecimal ivaAmount,
                        Instant createdAt) {
        this.id = id;
        this.invoiceId = invoiceId;
        this.lineNumber = lineNumber;
        this.productCode = productCode;
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.netAmount = netAmount;
        this.ivaAmount = ivaAmount;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public BigDecimal getIvaAmount() {
        return ivaAmount;
    }

    public String getIvaTaxCode() {
        return ivaTaxCode;
    }

    public String getInvoiceId() {
        return invoiceId;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public BigDecimal getIecAmount() {
        return iecAmount;
    }

    public BigDecimal getIsAmount() {
        return isAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
