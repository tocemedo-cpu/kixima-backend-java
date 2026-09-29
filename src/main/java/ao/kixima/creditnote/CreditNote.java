package ao.kixima.creditnote;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.faturacao.FaturacaoService;
import ao.kixima.invoice.Invoice;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Espelha o modelo Prisma `CreditNote` (schema.prisma:1235-1281, tabela
 * `credit_notes`) — a CORREÇÃO fiscal de uma fatura já emitida. A fatura é
 * imutável (série, número, hash); a nota de crédito regista a correção sem
 * apagar o que aconteceu, sempre referenciando a fatura original.
 */
@Entity
@Table(name = "credit_notes")
public class CreditNote extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "invoice_id", nullable = false)
    private String invoiceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", insertable = false, updatable = false)
    private Invoice invoice;

    @Column(nullable = false)
    private String motivo;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "net_amount", precision = 14, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "tax_amount", precision = 14, scale = 2)
    private BigDecimal taxAmount;

    @Column(nullable = false)
    private String currency = "AOA";

    private String serie;

    @Column(name = "numero_na_serie")
    private Integer numeroNaSerie;

    @Column(name = "hash_documento")
    private String hashDocumento;

    @Column(name = "hash_anterior")
    private String hashAnterior;

    @Column(name = "assinada_em")
    private Instant assinadaEm;

    @Column(name = "agt_document_no")
    private String agtDocumentNo;

    @Column(name = "agt_request_id")
    private String agtRequestId;

    @Column(name = "agt_result_code")
    private String agtResultCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agt_erro")
    private String agtErro;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agt_estado")
    private String agtEstado;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "created_by_id")
    private String createdById;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CreditNote() {
        // JPA
    }

    public CreditNote(String id, String reference, String invoiceId, String motivo, BigDecimal amount, BigDecimal netAmount,
                      BigDecimal taxAmount, String currency, String createdById, Instant issuedAt,
                      FaturacaoService.Certificacao certificacao) {
        this.id = id;
        this.reference = reference;
        this.invoiceId = invoiceId;
        this.motivo = motivo;
        this.amount = amount;
        this.netAmount = netAmount;
        this.taxAmount = taxAmount;
        this.currency = currency == null ? "AOA" : currency;
        this.createdById = createdById;
        this.issuedAt = issuedAt;
        this.createdAt = issuedAt;
        this.serie = certificacao.serie();
        this.numeroNaSerie = certificacao.numeroNaSerie();
        this.hashDocumento = certificacao.hashDocumento();
        this.hashAnterior = certificacao.hashAnterior();
        this.assinadaEm = certificacao.assinadaEm();
    }

    public String getId() {
        return id;
    }

    public String getReference() {
        return reference;
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

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getSerie() {
        return serie;
    }

    public Integer getNumeroNaSerie() {
        return numeroNaSerie;
    }

    public String getHashDocumento() {
        return hashDocumento;
    }

    public String getHashAnterior() {
        return hashAnterior;
    }

    public Instant getAssinadaEm() {
        return assinadaEm;
    }

    public String getAgtDocumentNo() {
        return agtDocumentNo;
    }

    public String getAgtRequestId() {
        return agtRequestId;
    }

    public String getAgtResultCode() {
        return agtResultCode;
    }

    public String getAgtErro() {
        return agtErro;
    }

    public String getAgtEstado() {
        return agtEstado;
    }

    /** Resultado da submissão EXPLÍCITA desta NC à AGT (anulação) — mesmo padrão de Invoice.agtRequestId/.../agtEstado. */
    public void registarSubmissaoAgt(String requestId, String resultCode, String erroJson, String estadoJson) {
        this.agtRequestId = requestId;
        this.agtResultCode = resultCode;
        this.agtErro = erroJson;
        this.agtEstado = estadoJson;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public String getCreatedById() {
        return createdById;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
