package ao.kixima.invoice;

import org.hibernate.annotations.UpdateTimestamp;
import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.contract.Contract;
import ao.kixima.po.PurchaseOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Espelha o modelo Prisma `Invoice` (schema.prisma:1103-1176, tabela
 * `invoices`). ÂMBITO: os campos de negócio, a cadeia de integridade local
 * (serie/numeroNaSerie/hash*) e, desde o M4, as colunas AGT (agtDocumentNo,
 * agtRequestId, agtResultCode, agtErro, agtEstado — ver AgtPayloadService).
 * `agtErro`/`agtEstado` são `jsonb` na base — mapeadas como texto JSON
 * bruto, mesmo padrão de Company.settings (M0/M1): um @Convert dedicado só
 * entra quando algum domínio precisar de os ler/escrever estruturadamente.
 * `contractId`/`consolidatedPoIds` são a fatura consolidada de call-offs
 * (ContractService.consolidateContractBilling) — `purchaseOrderId` fica
 * null nessas, estruturalmente: cobre várias POs.
 */
@Entity
@Table(name = "invoices")
public class Invoice extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "purchase_order_id", unique = true)
    private String purchaseOrderId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", insertable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "net_amount", precision = 14, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "tax_amount", precision = 14, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "withholding_amount", precision = 14, scale = 2)
    private BigDecimal withholdingAmount;

    @Column(nullable = false)
    private String currency = "AOA";

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private InvoiceStatus status = InvoiceStatus.PENDENTE;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    private String serie;

    @Column(name = "numero_na_serie")
    private Integer numeroNaSerie;

    @Column(name = "hash_documento")
    private String hashDocumento;

    @Column(name = "hash_anterior")
    private String hashAnterior;

    @Column(name = "assinada_em")
    private Instant assinadaEm;

    @Column(name = "referencia_pagamento", unique = true)
    private String referenciaPagamento;

    @Column(name = "contract_id")
    private String contractId;

    /** O contrato-quadro da fatura consolidada (`include: { contract: true }` do Node) — só de leitura. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    /** ids das call-offs cobertas por esta fatura consolidada — `text[]`. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "consolidated_po_ids")
    private List<String> consolidatedPoIds = new ArrayList<>();

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "invoice", fetch = FetchType.LAZY)
    @OrderBy("lineNumber ASC")
    private List<InvoiceLine> lines = new ArrayList<>();

    protected Invoice() {
        // JPA
    }

    public Invoice(String id, String reference, String purchaseOrderId, BigDecimal amount, BigDecimal netAmount,
                    BigDecimal taxAmount, BigDecimal withholdingAmount, String currency, InvoiceStatus status,
                    Instant issuedAt, Instant dueAt, String serie, Integer numeroNaSerie, String hashDocumento,
                    String hashAnterior, Instant assinadaEm, String referenciaPagamento, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.reference = reference;
        this.purchaseOrderId = purchaseOrderId;
        this.amount = amount;
        this.netAmount = netAmount;
        this.taxAmount = taxAmount;
        this.withholdingAmount = withholdingAmount;
        this.currency = currency;
        this.status = status;
        this.issuedAt = issuedAt;
        this.dueAt = dueAt;
        this.serie = serie;
        this.numeroNaSerie = numeroNaSerie;
        this.hashDocumento = hashDocumento;
        this.hashAnterior = hashAnterior;
        this.assinadaEm = assinadaEm;
        this.referenciaPagamento = referenciaPagamento;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public String getContractId() {
        return contractId;
    }

    public List<String> getConsolidatedPoIds() {
        return consolidatedPoIds;
    }

    public Contract getContract() {
        return contract;
    }

    /**
     * Espelha creditNoteService.partesDaFatura: o fornecedor da fatura vem da PO
     * ou, na fatura consolidada de call-offs, do contrato-quadro.
     */
    public String supplierCompanyId() {
        if (purchaseOrder != null) return purchaseOrder.getSupplierCompanyId();
        return contract == null ? null : contract.getSupplierCompanyId();
    }

    /** Idem para o comprador: `purchaseOrder.buyerCompanyId ?? contract.clientCompanyId`. */
    public String buyerCompanyId() {
        if (purchaseOrder != null) return purchaseOrder.getBuyerCompanyId();
        return contract == null ? null : contract.getClientCompanyId();
    }

    /**
     * Fatura consolidada: liga-a ao contrato e às call-offs que cobre (só na criação).
     * Recebe a entidade, não só o id, para a relação {@code contract} ficar disponível
     * já na mesma transação/sessão em que a fatura nasce (a coluna é só-leitura no JPA).
     */
    public void consolidarCallOffs(Contract contract, List<String> poIds) {
        this.contractId = contract.getId();
        this.contract = contract;
        this.consolidatedPoIds = new ArrayList<>(poIds);
    }

    public String getPurchaseOrderId() {
        return purchaseOrderId;
    }

    public PurchaseOrder getPurchaseOrder() {
        return purchaseOrder;
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

    public BigDecimal getWithholdingAmount() {
        return withholdingAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public void setStatus(InvoiceStatus status) {
        this.status = status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public String getSerie() {
        return serie;
    }

    public Integer getNumeroNaSerie() {
        return numeroNaSerie;
    }

    public String getHashAnterior() {
        return hashAnterior;
    }

    public String getHashDocumento() {
        return hashDocumento;
    }

    public String getReferenciaPagamento() {
        return referenciaPagamento;
    }

    public void setReferenciaPagamento(String referenciaPagamento) {
        this.referenciaPagamento = referenciaPagamento;
    }

    public List<InvoiceLine> getLines() {
        return lines;
    }

    public Instant getAssinadaEm() {
        return assinadaEm;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getAgtDocumentNo() {
        return agtDocumentNo;
    }

    public void setAgtDocumentNo(String agtDocumentNo) {
        this.agtDocumentNo = agtDocumentNo;
    }

    public String getAgtRequestId() {
        return agtRequestId;
    }

    public void setAgtRequestId(String agtRequestId) {
        this.agtRequestId = agtRequestId;
    }

    public String getAgtResultCode() {
        return agtResultCode;
    }

    public void setAgtResultCode(String agtResultCode) {
        this.agtResultCode = agtResultCode;
    }

    public String getAgtErro() {
        return agtErro;
    }

    public void setAgtErro(String agtErro) {
        this.agtErro = agtErro;
    }

    public String getAgtEstado() {
        return agtEstado;
    }

    public void setAgtEstado(String agtEstado) {
        this.agtEstado = agtEstado;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
