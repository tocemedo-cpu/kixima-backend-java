package ao.kixima.conciliacao;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.invoice.Invoice;
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
 * Espelha o modelo Prisma `LinhaExtrato` (schema.prisma:1349-1369, tabela
 * `linhas_extrato`) — uma linha do extrato bancário importado. Fica GUARDADA
 * mesmo quando não casa com fatura nenhuma: "não encontrámos nada" e "entrou
 * mas não casou" são respostas diferentes.
 */
@Entity
@Table(name = "linhas_extrato")
public class LinhaExtrato extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "id_no_banco", nullable = false, unique = true)
    private String idNoBanco;

    @Column(name = "data_valor", nullable = false)
    private Instant dataValor;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal montante;

    @Column(nullable = false)
    private String moeda = "AOA";

    private String descricao;

    private String referencia;

    @Column(nullable = false)
    private String estado = ConciliacaoService.POR_CONCILIAR;

    @Column(name = "invoice_id")
    private String invoiceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", insertable = false, updatable = false)
    private Invoice invoice;

    private String motivo;

    @Column(name = "importada_em", nullable = false)
    private Instant importadaEm;

    @Column(name = "conciliada_em")
    private Instant conciliadaEm;

    protected LinhaExtrato() {
        // JPA
    }

    public LinhaExtrato(String id, String idNoBanco, Instant dataValor, BigDecimal montante, String moeda, String descricao,
                        String referencia, Instant importadaEm) {
        this.id = id;
        this.idNoBanco = idNoBanco;
        this.dataValor = dataValor;
        this.montante = montante;
        this.moeda = moeda == null ? "AOA" : moeda;
        this.descricao = descricao;
        this.referencia = referencia;
        this.importadaEm = importadaEm;
    }

    public String getId() {
        return id;
    }

    public String getIdNoBanco() {
        return idNoBanco;
    }

    public Instant getDataValor() {
        return dataValor;
    }

    public BigDecimal getMontante() {
        return montante;
    }

    public String getMoeda() {
        return moeda;
    }

    public String getDescricao() {
        return descricao;
    }

    public String getReferencia() {
        return referencia;
    }

    public void setReferencia(String referencia) {
        this.referencia = referencia;
    }

    public String getEstado() {
        return estado;
    }

    public String getInvoiceId() {
        return invoiceId;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public String getMotivo() {
        return motivo;
    }

    /** Espelha `marcar()` — estado + motivo + fatura, com `conciliadaEm` só quando CONCILIADA. */
    public void marcar(String estado, String motivo, String invoiceId) {
        this.estado = estado;
        this.motivo = motivo;
        this.invoiceId = invoiceId;
        this.conciliadaEm = ConciliacaoService.CONCILIADA.equals(estado) ? Instant.now() : null;
    }

    public Instant getImportadaEm() {
        return importadaEm;
    }

    public Instant getConciliadaEm() {
        return conciliadaEm;
    }
}
