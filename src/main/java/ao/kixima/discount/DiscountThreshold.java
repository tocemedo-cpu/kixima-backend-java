package ao.kixima.discount;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Espelha o modelo Prisma `DiscountThreshold` (schema.prisma:1931-1941,
 * tabela `discount_thresholds`) — patamar de desconto por economia de
 * escala, CONFIGURÁVEL EM RUNTIME pelo Admin do Sistema (ver
 * {@link DiscountThresholdService}). Os valores 2,5%/5%/10% do desenho
 * original são só o seed inicial, nunca uma constante no código.
 */
@Entity
@Table(name = "discount_thresholds")
public class DiscountThreshold extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "min_volume_usd", nullable = false, precision = 16, scale = 2)
    private BigDecimal minVolumeUsd;

    @Column(name = "discount_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercent;

    @Column(nullable = false)
    private boolean ativo = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DiscountThreshold() {
        // JPA
    }

    public DiscountThreshold(String id, BigDecimal minVolumeUsd, BigDecimal discountPercent, boolean ativo, Instant createdAt) {
        this.id = id;
        this.minVolumeUsd = minVolumeUsd;
        this.discountPercent = discountPercent;
        this.ativo = ativo;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public BigDecimal getMinVolumeUsd() {
        return minVolumeUsd;
    }

    public void setMinVolumeUsd(BigDecimal minVolumeUsd) {
        this.minVolumeUsd = minVolumeUsd;
    }

    public BigDecimal getDiscountPercent() {
        return discountPercent;
    }

    public void setDiscountPercent(BigDecimal discountPercent) {
        this.discountPercent = discountPercent;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
