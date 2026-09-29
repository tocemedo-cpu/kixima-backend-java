package ao.kixima.company;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/** Espelha `model BudgetLimit` (tabela budget_limits) — limite mensal de orçamento de uma empresa compradora (1:1). */
@Entity
@Table(name = "budget_limits")
public class BudgetLimit extends AbstractPersistableEntity<String> {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(name = "company_id", nullable = false, unique = true)
    private String companyId;

    @Column(name = "period_monthly", nullable = false, precision = 14, scale = 2)
    private BigDecimal periodMonthly;

    @Column(nullable = false)
    private String currency = "AOA";

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BudgetLimit() {
    }

    public BudgetLimit(String id, String companyId, BigDecimal periodMonthly, String currency) {
        this.id = id;
        this.companyId = companyId;
        this.periodMonthly = periodMonthly;
        this.currency = currency;
        this.updatedAt = Instant.now();
    }

    public void atualizar(BigDecimal periodMonthly, String currency) {
        this.periodMonthly = periodMonthly;
        this.currency = currency;
    }

    @Override
    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public BigDecimal getPeriodMonthly() {
        return periodMonthly;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
