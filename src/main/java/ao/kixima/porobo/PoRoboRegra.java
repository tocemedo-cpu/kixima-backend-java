package ao.kixima.porobo;

import ao.kixima.catalog.Product;
import ao.kixima.common.persistence.AbstractPersistableEntity;
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

/**
 * Espelha o modelo Prisma `PoRoboRegra` (schema.prisma:1059-1090, tabela
 * `po_robo_regras`) — uma regra do Automatic PO Robot: por produto,
 * periodicidade, média aceite da IA ou definida à mão, limite de segurança.
 * {@code proximaExecucaoEm} só avança DEPOIS de criar a PO com sucesso — é
 * isto que impede duplicar a PO se o job correr duas vezes no mesmo ciclo.
 */
@Entity
@Table(name = "po_robo_regras")
public class PoRoboRegra extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "media_origem", nullable = false)
    private PoRoboMediaOrigem mediaOrigem = PoRoboMediaOrigem.IA;

    @Column(name = "media_mensal", nullable = false, precision = 14, scale = 3)
    private BigDecimal mediaMensal;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private PoRoboPeriodicidade periodicidade = PoRoboPeriodicidade.MENSAL;

    private Integer quantidade;

    @Column(nullable = false)
    private boolean ativo = true;

    @Column(name = "limite_maximo_usd", precision = 14, scale = 2)
    private BigDecimal limiteMaximoUsd;

    @Column(name = "proxima_execucao_em", nullable = false)
    private Instant proximaExecucaoEm;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PoRoboRegra() {
        // JPA
    }

    public PoRoboRegra(String id, String companyId, String productId, PoRoboMediaOrigem mediaOrigem,
                       BigDecimal mediaMensal, PoRoboPeriodicidade periodicidade, Integer quantidade,
                       BigDecimal limiteMaximoUsd, Instant proximaExecucaoEm, Instant createdAt) {
        this.id = id;
        this.companyId = companyId;
        this.productId = productId;
        this.mediaOrigem = mediaOrigem;
        this.mediaMensal = mediaMensal;
        this.periodicidade = periodicidade;
        this.quantidade = quantidade;
        this.limiteMaximoUsd = limiteMaximoUsd;
        this.proximaExecucaoEm = proximaExecucaoEm;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getProductId() {
        return productId;
    }

    public Product getProduct() {
        return product;
    }

    public PoRoboMediaOrigem getMediaOrigem() {
        return mediaOrigem;
    }

    public void setMediaOrigem(PoRoboMediaOrigem mediaOrigem) {
        this.mediaOrigem = mediaOrigem;
    }

    public BigDecimal getMediaMensal() {
        return mediaMensal;
    }

    public void setMediaMensal(BigDecimal mediaMensal) {
        this.mediaMensal = mediaMensal;
    }

    public PoRoboPeriodicidade getPeriodicidade() {
        return periodicidade;
    }

    public void setPeriodicidade(PoRoboPeriodicidade periodicidade) {
        this.periodicidade = periodicidade;
    }

    public Integer getQuantidade() {
        return quantidade;
    }

    public void setQuantidade(Integer quantidade) {
        this.quantidade = quantidade;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public void setAtivo(boolean ativo) {
        this.ativo = ativo;
    }

    public BigDecimal getLimiteMaximoUsd() {
        return limiteMaximoUsd;
    }

    public void setLimiteMaximoUsd(BigDecimal limiteMaximoUsd) {
        this.limiteMaximoUsd = limiteMaximoUsd;
    }

    public Instant getProximaExecucaoEm() {
        return proximaExecucaoEm;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
