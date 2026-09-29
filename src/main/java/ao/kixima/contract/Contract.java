package ao.kixima.contract;

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
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Espelha `model Contract` (tabela contracts) — contrato-quadro entre cliente e fornecedor (secção 5 da especificação). */
@Entity
@Table(name = "contracts")
public class Contract extends AbstractPersistableEntity<String> {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "client_company_id", nullable = false)
    private String clientCompanyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_company_id", insertable = false, updatable = false)
    private Company clientCompany;

    @Column(name = "supplier_company_id", nullable = false)
    private String supplierCompanyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_company_id", insertable = false, updatable = false)
    private Company supplierCompany;

    /** Categorias/itens cobertos pelo contrato — `text[]`, mesmo mapeamento de User.adminAreas. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "categories_covered")
    private List<String> categoriesCovered = new ArrayList<>();

    @Column(name = "total_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalValue;

    @Column(nullable = false)
    private String currency = "AOA";

    @Column(name = "used_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal usedValue = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "billing_periodicity", nullable = false)
    private BillingPeriodicity billingPeriodicity;

    /** Prazo próprio do contrato (substitui os 7 dias padrão). */
    @Column(name = "payment_term_days", nullable = false)
    private int paymentTermDays;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ContractStatus status = ContractStatus.ATIVO;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Contract() {
    }

    public Contract(String id, String reference, String clientCompanyId, String supplierCompanyId, List<String> categoriesCovered,
                    BigDecimal totalValue, String currency, BillingPeriodicity billingPeriodicity, int paymentTermDays,
                    Instant validFrom, Instant validUntil, Instant createdAt) {
        this.id = id;
        this.reference = reference;
        this.clientCompanyId = clientCompanyId;
        this.supplierCompanyId = supplierCompanyId;
        this.categoriesCovered = new ArrayList<>(categoriesCovered);
        this.totalValue = totalValue;
        this.currency = currency;
        this.billingPeriodicity = billingPeriodicity;
        this.paymentTermDays = paymentTermDays;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /** `usedValue: { increment }` — o tecto do contrato-quadro consome-se com o LÍQUIDO, não com o IVA. */
    public void consumir(BigDecimal liquido) {
        this.usedValue = this.usedValue.add(liquido);
    }

    public boolean cobre(List<String> categorias) {
        return categoriesCovered.containsAll(categorias);
    }

    @Override
    public String getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public String getClientCompanyId() {
        return clientCompanyId;
    }

    public Company getClientCompany() {
        return clientCompany;
    }

    public String getSupplierCompanyId() {
        return supplierCompanyId;
    }

    public Company getSupplierCompany() {
        return supplierCompany;
    }

    public List<String> getCategoriesCovered() {
        return categoriesCovered;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getUsedValue() {
        return usedValue;
    }

    public BillingPeriodicity getBillingPeriodicity() {
        return billingPeriodicity;
    }

    public int getPaymentTermDays() {
        return paymentTermDays;
    }

    public ContractStatus getStatus() {
        return status;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
