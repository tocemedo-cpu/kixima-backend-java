package ao.kixima.payment;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.faturacao.FaturacaoService;
import ao.kixima.invoice.Invoice;
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
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Espelha o modelo Prisma `Payment` (schema.prisma:1403-1445, tabela
 * `payments`) — o pagamento de uma fatura pelo Financeiro do comprador, com
 * comprovativo obrigatório. É também o documento "RC" (Recibo) da AGT:
 * série e cadeia de integridade próprias, mesmo mecanismo de Invoice/CreditNote.
 */
@Entity
@Table(name = "payments")
public class Payment extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "invoice_id", nullable = false)
    private String invoiceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", insertable = false, updatable = false)
    private Invoice invoice;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "AOA";

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.PROCESSADO;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private CanalPagamento canal = CanalPagamento.TRANSFERENCIA_MANUAL;

    @Column(name = "processed_by_id")
    private String processedById;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "proof_url")
    private String proofUrl;

    @Column(name = "proof_name")
    private String proofName;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "received_by_id")
    private String receivedById;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

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

    protected Payment() {
        // JPA
    }

    public Payment(String id, String invoiceId, BigDecimal amount, String currency, String processedById,
                   String reference, String proofUrl, String proofName, Instant processedAt,
                   FaturacaoService.Certificacao certificacao) {
        this.id = id;
        this.invoiceId = invoiceId;
        this.amount = amount;
        this.currency = currency == null ? "AOA" : currency;
        this.processedById = processedById;
        this.reference = reference;
        this.proofUrl = proofUrl;
        this.proofName = proofName;
        this.processedAt = processedAt;
        this.serie = certificacao.serie();
        this.numeroNaSerie = certificacao.numeroNaSerie();
        this.hashDocumento = certificacao.hashDocumento();
        this.hashAnterior = certificacao.hashAnterior();
        this.assinadaEm = certificacao.assinadaEm();
    }

    /** Espelha o `payment.create` de conciliacaoService.tentarConciliar — canal REFERENCIA_BANCARIA, sem comprovativo. */
    public static Payment conciliado(String id, String invoiceId, BigDecimal amount, String currency, String processedById,
                                     String reference, Instant processedAt, FaturacaoService.Certificacao certificacao) {
        Payment p = new Payment(id, invoiceId, amount, currency, processedById, reference, null, null, processedAt, certificacao);
        p.canal = CanalPagamento.REFERENCIA_BANCARIA;
        return p;
    }

    /** Espelha o `payment.create` de poService.aplicarPagamentoErp — canal ERP, sem executor KIXIMA nem comprovativo. */
    public static Payment confirmadoPeloErp(String id, String invoiceId, BigDecimal amount, String currency, String reference,
                                            Instant processedAt, FaturacaoService.Certificacao certificacao) {
        Payment p = new Payment(id, invoiceId, amount, currency, null, reference, null, null, processedAt, certificacao);
        p.canal = CanalPagamento.ERP;
        return p;
    }

    public String getId() {
        return id;
    }

    public String getInvoiceId() {
        return invoiceId;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public CanalPagamento getCanal() {
        return canal;
    }

    public String getProcessedById() {
        return processedById;
    }

    public String getReference() {
        return reference;
    }

    public String getProofUrl() {
        return proofUrl;
    }

    public String getProofName() {
        return proofName;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getReceivedById() {
        return receivedById;
    }

    public void confirmarRececao(Instant quando, String porQuem) {
        this.receivedAt = quando;
        this.receivedById = porQuem;
    }

    public Instant getProcessedAt() {
        return processedAt;
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
}
