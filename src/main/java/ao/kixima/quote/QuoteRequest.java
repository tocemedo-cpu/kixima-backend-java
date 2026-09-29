package ao.kixima.quote;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.company.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Espelha `model QuoteRequest` (tabela quote_requests) — pedido de cotação (RFQ) do comprador a um fornecedor. */
@Entity
@Table(name = "quote_requests")
public class QuoteRequest extends AbstractPersistableEntity<String> {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(name = "buyer_company_id", nullable = false)
    private String buyerCompanyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_company_id", insertable = false, updatable = false)
    private Company buyerCompany;

    @Column(name = "supplier_company_id", nullable = false)
    private String supplierCompanyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_company_id", insertable = false, updatable = false)
    private Company supplierCompany;

    @Column(name = "created_by_id", nullable = false)
    private String createdById;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private QuoteStatus status = QuoteStatus.ABERTA;

    @Column
    private String note;

    @Column(name = "response_price", precision = 14, scale = 2)
    private BigDecimal responsePrice;

    @Column(name = "response_lead_days")
    private Integer responseLeadDays;

    @Column(name = "response_note")
    private String responseNote;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "quoteRequest", fetch = FetchType.LAZY)
    private List<QuoteItem> items = new ArrayList<>();

    protected QuoteRequest() {
    }

    public QuoteRequest(String id, String buyerCompanyId, String supplierCompanyId, String createdById, String note, Instant createdAt) {
        this.id = id;
        this.buyerCompanyId = buyerCompanyId;
        this.supplierCompanyId = supplierCompanyId;
        this.createdById = createdById;
        this.note = note;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /** `respond`: RESPONDIDA + preço/prazo/nota + respondedAt. */
    public void responder(BigDecimal price, Integer leadDays, String note, Instant agora) {
        this.status = QuoteStatus.RESPONDIDA;
        this.responsePrice = price;
        this.responseLeadDays = leadDays;
        this.responseNote = note;
        this.respondedAt = agora;
    }

    public void encerrar() {
        this.status = QuoteStatus.FECHADA;
    }

    @Override
    public String getId() {
        return id;
    }

    public String getBuyerCompanyId() {
        return buyerCompanyId;
    }

    public Company getBuyerCompany() {
        return buyerCompany;
    }

    public String getSupplierCompanyId() {
        return supplierCompanyId;
    }

    public Company getSupplierCompany() {
        return supplierCompany;
    }

    public String getCreatedById() {
        return createdById;
    }

    public QuoteStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public BigDecimal getResponsePrice() {
        return responsePrice;
    }

    public Integer getResponseLeadDays() {
        return responseLeadDays;
    }

    public String getResponseNote() {
        return responseNote;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<QuoteItem> getItems() {
        return items;
    }
}
