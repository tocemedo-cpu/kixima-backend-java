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
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Proposta de compra preparada pelo robot (tabela {@code po_robo_propostas}, V4). Nunca é uma ordem de compra: só uma
 * pessoa a transforma em PO ({@code PoRoboPropostaService#submeter}), e a PO nasce em AGUARDANDO_APROVACAO.
 * A quantidade é sempre &gt; 0; {@code quantidadeOriginal} guarda o que o robot calculou antes de qualquer redução
 * pelo limite de segurança ou ajuste humano.
 */
@Entity
@Table(name = "po_robo_propostas")
public class PoRoboProposta extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(name = "regra_id")
    private String regraId;

    @Column(name = "previsao_id")
    private String previsaoId;

    @Column(name = "execucao_id")
    private String execucaoId;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    private Product product;

    @Column(name = "supplier_id", nullable = false)
    private String supplierId;

    @Column(nullable = false)
    private int quantidade;

    @Column(name = "quantidade_original", nullable = false)
    private int quantidadeOriginal;

    @Column(name = "preco_unitario", precision = 14, scale = 2)
    private BigDecimal precoUnitario;

    @Column(nullable = false)
    private String moeda;

    @Column(name = "valor_total", precision = 14, scale = 2)
    private BigDecimal valorTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoProposta estado = EstadoProposta.PENDENTE_REVISAO;

    @Column(name = "criada_em", nullable = false)
    private Instant criadaEm;

    @Column(name = "expirada_em")
    private Instant expiradaEm;

    @Column(name = "motivo_expiracao")
    private String motivoExpiracao;

    @Column(nullable = false)
    private String justificacao = "[]";

    @Column(name = "limite_aplicado", nullable = false)
    private boolean limiteAplicado;

    @Column(name = "purchase_order_id")
    private String purchaseOrderId;

    @Column(name = "purchase_order_reference")
    private String purchaseOrderReference;

    @Column(name = "motivo_rejeicao")
    private String motivoRejeicao;

    @Column(name = "revista_por_id")
    private String revistaPorId;

    @Column(name = "revista_em")
    private Instant revistaEm;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PoRoboProposta() {
        // JPA
    }

    public PoRoboProposta(String id, String companyId, String regraId, String previsaoId, String execucaoId, String productId,
                          String supplierId, int quantidade, int quantidadeOriginal, BigDecimal precoUnitario, String moeda,
                          String justificacaoJson, boolean limiteAplicado, Instant criadaEm) {
        this.id = id;
        this.companyId = companyId;
        this.regraId = regraId;
        this.previsaoId = previsaoId;
        this.execucaoId = execucaoId;
        this.productId = productId;
        this.supplierId = supplierId;
        this.quantidade = quantidade;
        this.quantidadeOriginal = quantidadeOriginal;
        this.precoUnitario = precoUnitario;
        this.moeda = moeda;
        this.valorTotal = total(precoUnitario, quantidade);
        this.justificacao = justificacaoJson;
        this.limiteAplicado = limiteAplicado;
        this.criadaEm = criadaEm;
    }

    private static BigDecimal total(BigDecimal precoUnitario, int quantidade) {
        return precoUnitario == null ? null : precoUnitario.multiply(BigDecimal.valueOf(quantidade));
    }

    public boolean pendente() {
        return estado == EstadoProposta.PENDENTE_REVISAO;
    }

    /** Ajuste humano da quantidade — o valor total acompanha; a quantidade original do robot não muda. */
    public void ajustarQuantidade(int novaQuantidade) {
        this.quantidade = novaQuantidade;
        this.valorTotal = total(precoUnitario, novaQuantidade);
    }

    public void expirar(Instant em, String motivo) {
        this.estado = EstadoProposta.EXPIRADA;
        this.expiradaEm = em;
        this.motivoExpiracao = motivo;
    }

    public void marcarSubmetida(String purchaseOrderId, String purchaseOrderReference, String revistaPorId, Instant em) {
        this.estado = EstadoProposta.SUBMETIDA;
        this.purchaseOrderId = purchaseOrderId;
        this.purchaseOrderReference = purchaseOrderReference;
        this.revistaPorId = revistaPorId;
        this.revistaEm = em;
    }

    public void rejeitar(String motivo, String revistaPorId, Instant em) {
        this.estado = EstadoProposta.REJEITADA;
        this.motivoRejeicao = motivo;
        this.revistaPorId = revistaPorId;
        this.revistaEm = em;
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

    public String getPrevisaoId() {
        return previsaoId;
    }

    public String getExecucaoId() {
        return execucaoId;
    }

    public String getProductId() {
        return productId;
    }

    public Product getProduct() {
        return product;
    }

    public String getSupplierId() {
        return supplierId;
    }

    public int getQuantidade() {
        return quantidade;
    }

    public int getQuantidadeOriginal() {
        return quantidadeOriginal;
    }

    public BigDecimal getPrecoUnitario() {
        return precoUnitario;
    }

    public String getMoeda() {
        return moeda;
    }

    public BigDecimal getValorTotal() {
        return valorTotal;
    }

    public EstadoProposta getEstado() {
        return estado;
    }

    public Instant getCriadaEm() {
        return criadaEm;
    }

    public Instant getExpiradaEm() {
        return expiradaEm;
    }

    public String getMotivoExpiracao() {
        return motivoExpiracao;
    }

    public String getJustificacao() {
        return justificacao;
    }

    public boolean isLimiteAplicado() {
        return limiteAplicado;
    }

    public String getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public String getPurchaseOrderReference() {
        return purchaseOrderReference;
    }

    public String getMotivoRejeicao() {
        return motivoRejeicao;
    }

    public String getRevistaPorId() {
        return revistaPorId;
    }

    public Instant getRevistaEm() {
        return revistaEm;
    }
}
