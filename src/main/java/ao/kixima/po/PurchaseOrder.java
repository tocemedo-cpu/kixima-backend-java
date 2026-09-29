package ao.kixima.po;

import org.hibernate.annotations.UpdateTimestamp;
import ao.kixima.common.persistence.AbstractPersistableEntity;
import ao.kixima.company.Company;
import ao.kixima.invoice.Invoice;
import ao.kixima.user.User;
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
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Espelha o modelo Prisma `PurchaseOrder` (schema.prisma:891-982, tabela
 * `purchase_orders`) — a máquina de estados central (sec. 3 do manual).
 *
 * Cobre o fluxo humano completo (checkout → aprovação → aceitação → despacho
 * → entrega → receção → fecho) e, desde o fecho de lacunas A.5, a call-off
 * (`contractId`/`consolidatedInvoiceId`, domínio Contract). NÃO incluído
 * ainda (colunas existem na tabela mas ficam de fora do mapeamento, não
 * bloqueiam `ddl-auto=validate`): `erpSyncLogs` (aplicarDecisaoErp/
 * aplicarPagamentoErp — callback ERP, lacuna A.7).
 */
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String reference;

    @Column(name = "buyer_company_id", nullable = false)
    private String buyerCompanyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_company_id", insertable = false, updatable = false)
    private Company buyerCompany;

    @Column(name = "supplier_company_id", nullable = false)
    private String supplierCompanyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_company_id", insertable = false, updatable = false)
    private Company supplierCompany;

    @Column(name = "created_by_id", nullable = false)
    private String createdById;

    @Column(name = "approved_by_id")
    private String approvedById;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private PoStatus status = PoStatus.AGUARDANDO_APROVACAO;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "net_amount", precision = 14, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "tax_amount", precision = 14, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "withholding_amount", precision = 14, scale = 2)
    private BigDecimal withholdingAmount;

    @Column(nullable = false)
    private String currency = "AOA";

    @Column(name = "is_call_off", nullable = false)
    private boolean isCallOff = false;

    /** Contrato-quadro ao abrigo do qual esta PO nasceu como call-off (null nas POs normais). */
    @Column(name = "contract_id")
    private String contractId;

    /** Fatura consolidada que já cobriu esta call-off — é ESTE campo que sabe se ela já foi faturada. */
    @Column(name = "consolidated_invoice_id")
    private String consolidatedInvoiceId;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "payment_due_at")
    private Instant paymentDueAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "reception_status")
    private String receptionStatus;

    @Column(name = "divergence_resolution")
    private String divergenceResolution;

    @Column(name = "divergence_resolution_notes")
    private String divergenceResolutionNotes;

    @Column(name = "divergence_resolved_at")
    private Instant divergenceResolvedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "refused_at")
    private Instant refusedAt;

    @Column(name = "refusal_reason")
    private String refusalReason;

    @Column(name = "erp_managed", nullable = false)
    private boolean erpManaged = false;

    @Column(name = "erp_external_id")
    private String erpExternalId;

    @Column(name = "erp_approval_requested_at")
    private Instant erpApprovalRequestedAt;

    @Column(name = "created_by_source", nullable = false)
    private String createdBySource = "HUMANO";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "purchaseOrder", fetch = FetchType.LAZY)
    private List<PurchaseOrderItem> items = new ArrayList<>();

    @OneToOne(mappedBy = "purchaseOrder", fetch = FetchType.LAZY)
    private Invoice invoice;

    protected PurchaseOrder() {
        // JPA
    }

    public PurchaseOrder(String id, String reference, String buyerCompanyId, String supplierCompanyId,
                          String createdById, PoStatus status, BigDecimal totalAmount, BigDecimal netAmount,
                          BigDecimal taxAmount, BigDecimal withholdingAmount, boolean isCallOff, boolean erpManaged,
                          Instant erpApprovalRequestedAt, String createdBySource, Instant approvedAt,
                          Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.reference = reference;
        this.buyerCompanyId = buyerCompanyId;
        this.supplierCompanyId = supplierCompanyId;
        this.createdById = createdById;
        this.status = status;
        this.totalAmount = totalAmount;
        this.netAmount = netAmount;
        this.taxAmount = taxAmount;
        this.withholdingAmount = withholdingAmount;
        this.isCallOff = isCallOff;
        this.erpManaged = erpManaged;
        this.erpApprovalRequestedAt = erpApprovalRequestedAt;
        this.createdBySource = createdBySource;
        this.approvedAt = approvedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // --- getters -----------------------------------------------------------

    public String getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public String getBuyerCompanyId() {
        return buyerCompanyId;
    }

    public Company getBuyerCompany() {
        return buyerCompany;
    }

    public String getSupplierCompanyId() {
        return supplierCompanyId;
    }

    public Company getSupplierCompany() {
        return supplierCompany;
    }

    public String getCreatedById() {
        return createdById;
    }

    public String getApprovedById() {
        return approvedById;
    }

    public void setApprovedById(String approvedById) {
        this.approvedById = approvedById;
    }

    public PoStatus getStatus() {
        return status;
    }

    public void setStatus(PoStatus status) {
        this.status = status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
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

    public boolean isCallOff() {
        return isCallOff;
    }

    public String getContractId() {
        return contractId;
    }

    /** Checkout dentro da cobertura de um contrato-quadro: a PO nasce call-off, já APROVADA. */
    public void nascerComoCallOff(String contractId, Instant approvedAt) {
        this.isCallOff = true;
        this.contractId = contractId;
        this.status = PoStatus.APROVADA;
        this.approvedAt = approvedAt;
    }

    public String getConsolidatedInvoiceId() {
        return consolidatedInvoiceId;
    }

    public void setConsolidatedInvoiceId(String consolidatedInvoiceId) {
        this.consolidatedInvoiceId = consolidatedInvoiceId;
    }

    public boolean isErpManaged() {
        return erpManaged;
    }

    public String getErpExternalId() {
        return erpExternalId;
    }

    public void setErpExternalId(String erpExternalId) {
        this.erpExternalId = erpExternalId;
    }

    public Instant getErpApprovalRequestedAt() {
        return erpApprovalRequestedAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public Instant getPaymentDueAt() {
        return paymentDueAt;
    }

    public void setPaymentDueAt(Instant paymentDueAt) {
        this.paymentDueAt = paymentDueAt;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Instant dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getReceptionStatus() {
        return receptionStatus;
    }

    public void setReceptionStatus(String receptionStatus) {
        this.receptionStatus = receptionStatus;
    }

    public String getDivergenceResolution() {
        return divergenceResolution;
    }

    public void setDivergenceResolution(String divergenceResolution) {
        this.divergenceResolution = divergenceResolution;
    }

    public void setDivergenceResolutionNotes(String divergenceResolutionNotes) {
        this.divergenceResolutionNotes = divergenceResolutionNotes;
    }

    public void setDivergenceResolvedAt(Instant divergenceResolvedAt) {
        this.divergenceResolvedAt = divergenceResolvedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public void setRejectedAt(Instant rejectedAt) {
        this.rejectedAt = rejectedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Instant getRefusedAt() {
        return refusedAt;
    }

    public void setRefusedAt(Instant refusedAt) {
        this.refusedAt = refusedAt;
    }

    public String getRefusalReason() {
        return refusalReason;
    }

    public void setRefusalReason(String refusalReason) {
        this.refusalReason = refusalReason;
    }

    public String getDivergenceResolutionNotes() {
        return divergenceResolutionNotes;
    }

    public String getCreatedBySource() {
        return createdBySource;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<PurchaseOrderItem> getItems() {
        return items;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDivergenceResolvedAt() {
        return divergenceResolvedAt;
    }
}
