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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Previsão calculada pelo servidor numa execução (tabela {@code po_robo_previsoes}, V4). {@code stockActual} é sempre
 * {@code null}: não existe fonte fiável do inventário do comprador, e o stock do fornecedor nunca o substitui.
 */
@Entity
@Table(name = "po_robo_previsoes")
public class PoRoboPrevisao extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(name = "regra_id")
    private String regraId;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private Product product;

    @Column(name = "execucao_id")
    private String execucaoId;

    @Column(name = "gerada_em", nullable = false)
    private Instant geradaEm;

    @Column(name = "consumo_medio_mensal", nullable = false, precision = 14, scale = 3)
    private BigDecimal consumoMedioMensal;

    @Column(name = "stock_actual")
    private Integer stockActual;

    @Column(name = "stock_minimo")
    private Integer stockMinimo;

    @Column(name = "stock_seguranca")
    private Integer stockSeguranca;

    @Column(name = "prazo_entrega_dias")
    private Integer prazoEntregaDias;

    @Column(name = "ponto_encomenda", precision = 14, scale = 3)
    private BigDecimal pontoEncomenda;

    @Column(name = "quantidade_recomendada", nullable = false)
    private int quantidadeRecomendada;

    @Enumerated(EnumType.STRING)
    @Column(name = "origem_dados", nullable = false)
    private PoRoboOrigemDados origemDados;

    @Column(nullable = false)
    private String justificacao = "[]";

    protected PoRoboPrevisao() {
        // JPA
    }

    public PoRoboPrevisao(String id, String companyId, String regraId, String productId, String execucaoId, Instant geradaEm,
                          BigDecimal consumoMedioMensal, Integer stockMinimo, Integer stockSeguranca, Integer prazoEntregaDias,
                          BigDecimal pontoEncomenda, int quantidadeRecomendada, PoRoboOrigemDados origemDados, String justificacaoJson) {
        this.id = id;
        this.companyId = companyId;
        this.regraId = regraId;
        this.productId = productId;
        this.execucaoId = execucaoId;
        this.geradaEm = geradaEm;
        this.consumoMedioMensal = consumoMedioMensal;
        this.stockActual = null; // sem fonte fiável do stock do comprador
        this.stockMinimo = stockMinimo;
        this.stockSeguranca = stockSeguranca;
        this.prazoEntregaDias = prazoEntregaDias;
        this.pontoEncomenda = pontoEncomenda;
        this.quantidadeRecomendada = quantidadeRecomendada;
        this.origemDados = origemDados;
        this.justificacao = justificacaoJson;
    }

    @Override
    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getRegraId() {
        return regraId;
    }

    public String getProductId() {
        return productId;
    }

    public Product getProduct() {
        return product;
    }

    public String getExecucaoId() {
        return execucaoId;
    }

    public Instant getGeradaEm() {
        return geradaEm;
    }

    public BigDecimal getConsumoMedioMensal() {
        return consumoMedioMensal;
    }

    public Integer getStockActual() {
        return stockActual;
    }

    public Integer getStockMinimo() {
        return stockMinimo;
    }

    public Integer getStockSeguranca() {
        return stockSeguranca;
    }

    public Integer getPrazoEntregaDias() {
        return prazoEntregaDias;
    }

    public BigDecimal getPontoEncomenda() {
        return pontoEncomenda;
    }

    public int getQuantidadeRecomendada() {
        return quantidadeRecomendada;
    }

    public PoRoboOrigemDados getOrigemDados() {
        return origemDados;
    }

    public String getJustificacao() {
        return justificacao;
    }
}
