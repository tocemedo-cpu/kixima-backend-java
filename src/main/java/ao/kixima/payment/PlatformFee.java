package ao.kixima.payment;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Espelha o modelo Prisma `PlatformFee` (schema.prisma:375-400, tabela
 * `platform_fees`) — a taxa da plataforma (comissão KIXIMA), à parte da
 * PO/Fatura, cobrada ao fornecedor em USD. Guarda o valor em USD e o câmbio
 * usados, para o extrato ser auditável e o histórico não mudar com o câmbio.
 */
@Entity
@Table(name = "platform_fees")
public class PlatformFee extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(name = "invoice_id", nullable = false, unique = true)
    private String invoiceId;

    @Column(name = "po_count", nullable = false)
    private int poCount;

    @Column(name = "per_po", nullable = false, precision = 14, scale = 2)
    private BigDecimal perPo;

    @Column(name = "per_invoice", nullable = false, precision = 14, scale = 2)
    private BigDecimal perInvoice;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "USD";

    private String basis;

    @Column(name = "po_value_usd", precision = 16, scale = 2)
    private BigDecimal poValueUsd;

    @Column(name = "fx_rate", precision = 14, scale = 4)
    private BigDecimal fxRate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private PlatformFeeStatus status = PlatformFeeStatus.PENDENTE;

    @Column(name = "charged_at")
    private Instant chargedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PlatformFee() {
        // JPA
    }

    public PlatformFee(String id, String companyId, String invoiceId, int poCount, BigDecimal perPo, BigDecimal perInvoice,
                       BigDecimal amount, String currency, String basis, BigDecimal poValueUsd, BigDecimal fxRate,
                       Instant createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.invoiceId = invoiceId;
        this.poCount = poCount;
        this.perPo = perPo;
        this.perInvoice = perInvoice;
        this.amount = amount;
        this.currency = currency;
        this.basis = basis;
        this.poValueUsd = poValueUsd;
        this.fxRate = fxRate;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getInvoiceId() {
        return invoiceId;
    }

    public int getPoCount() {
        return poCount;
    }

    public BigDecimal getPerPo() {
        return perPo;
    }

    public BigDecimal getPerInvoice() {
        return perInvoice;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getBasis() {
        return basis;
    }

    public BigDecimal getPoValueUsd() {
        return poValueUsd;
    }

    public BigDecimal getFxRate() {
        return fxRate;
    }

    public PlatformFeeStatus getStatus() {
        return status;
    }

    public Instant getChargedAt() {
        return chargedAt;
    }

    public void marcarCobrada(Instant quando) {
        this.status = PlatformFeeStatus.COBRADO;
        this.chargedAt = quando;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
