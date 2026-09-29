package ao.kixima.po.dto;

import ao.kixima.company.Company;
import ao.kixima.invoice.dto.InvoiceDto;
import ao.kixima.po.PurchaseOrder;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Espelha a linha `PurchaseOrder` tal como o Prisma a devolve — todos os
 * escalares, {@code null} incluído — mais as relações que cada função do
 * poService.js inclui. Cada endpoint traz uma forma diferente
 * (o replay de contrato do M7 apanhou as diferenças):
 * <ul>
 *   <li>transições (approve/accept/dispatch/…): só escalares → {@link #escalar};</li>
 *   <li>criação: escalares + {@code items} (sem produto);</li>
 *   <li>listagem: + {@code items} e {@code invoice.payment};</li>
 *   <li>detalhe: + {@code items.product}, {@code invoice.{payment,creditNotes,lines}},
 *       {@code buyerCompany}/{@code supplierCompany} (COMPANY_FIELDS), {@code createdBy}/{@code approvedBy}
 *       (só o nome) e {@code contract.reference}.</li>
 * </ul>
 * As relações ausentes são omitidas (o `include` do Prisma omite a chave, não a deixa null).
 */
public record PurchaseOrderDto(
        String id, String reference, String buyerCompanyId, String supplierCompanyId,
        String createdById, String approvedById, String status,
        BigDecimal totalAmount, BigDecimal netAmount, BigDecimal taxAmount, BigDecimal withholdingAmount,
        String currency, boolean isCallOff, String contractId, String consolidatedInvoiceId, boolean erpManaged,
        String erpExternalId, Instant erpApprovalRequestedAt,
        Instant acceptedAt, Instant paymentDueAt, Instant paidAt, Instant dispatchedAt, Instant deliveredAt, Instant receivedAt,
        String receptionStatus, String divergenceResolution, String divergenceResolutionNotes, Instant divergenceResolvedAt,
        Instant approvedAt, Instant rejectedAt, String rejectionReason, Instant refusedAt, String refusalReason,
        String createdBySource, Instant createdAt, Instant updatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<PurchaseOrderItemDto> items,
        @JsonInclude(JsonInclude.Include.NON_NULL) InvoiceDto invoice,
        @JsonInclude(JsonInclude.Include.NON_NULL) CompanyRef buyerCompany,
        @JsonInclude(JsonInclude.Include.NON_NULL) CompanyRef supplierCompany,
        @JsonInclude(JsonInclude.Include.NON_NULL) NameRef createdBy,
        @JsonInclude(JsonInclude.Include.NON_NULL) NameRef approvedBy,
        @JsonInclude(JsonInclude.Include.NON_NULL) ContractRef contract
) {

    /** COMPANY_FIELDS do poService.js. */
    public record CompanyRef(String id, String name, String taxId, String contactEmail, String contactPhone, String address,
                             String logoUrl, String city, String province, String country, String bankName, String iban, String swift) {
        public static CompanyRef de(Company c) {
            return c == null ? null : new CompanyRef(c.getId(), c.getName(), c.getTaxId(), c.getContactEmail(), c.getContactPhone(),
                    c.getAddress(), c.getLogoUrl(), c.getCity(), c.getProvince(), c.getCountry(), c.getBankName(), c.getIban(), c.getSwift());
        }
    }

    /** `{ select: { name: true } }`. */
    public record NameRef(String name) {
    }

    /** `contract: { select: { reference: true } }`. */
    public record ContractRef(String reference) {
    }

    /** Só a linha da tabela — o que cada transição de estado devolve no Node (`prisma.purchaseOrder.update` sem include). */
    public static PurchaseOrderDto escalar(PurchaseOrder po) {
        return de(po, null, null, null, null, null, null, null);
    }

    /** Compatibilidade com quem mapeia PO + items sem produto (criação, listagens). */
    public static PurchaseOrderDto de(PurchaseOrder po) {
        return de(po, po.getItems().stream().map(i -> PurchaseOrderItemDto.de(i, null)).toList(), null, null, null, null, null, null);
    }

    public static PurchaseOrderDto de(PurchaseOrder po, List<PurchaseOrderItemDto> items, InvoiceDto invoice,
                                      CompanyRef buyerCompany, CompanyRef supplierCompany, NameRef createdBy, NameRef approvedBy,
                                      ContractRef contract) {
        return new PurchaseOrderDto(
                po.getId(), po.getReference(), po.getBuyerCompanyId(), po.getSupplierCompanyId(),
                po.getCreatedById(), po.getApprovedById(), po.getStatus().name(),
                po.getTotalAmount(), po.getNetAmount(), po.getTaxAmount(), po.getWithholdingAmount(),
                po.getCurrency(), po.isCallOff(), po.getContractId(), po.getConsolidatedInvoiceId(), po.isErpManaged(),
                po.getErpExternalId(), po.getErpApprovalRequestedAt(),
                po.getAcceptedAt(), po.getPaymentDueAt(), po.getPaidAt(), po.getDispatchedAt(), po.getDeliveredAt(), po.getReceivedAt(),
                po.getReceptionStatus(), po.getDivergenceResolution(), po.getDivergenceResolutionNotes(), po.getDivergenceResolvedAt(),
                po.getApprovedAt(), po.getRejectedAt(), po.getRejectionReason(), po.getRefusedAt(), po.getRefusalReason(),
                po.getCreatedBySource(), po.getCreatedAt(), po.getUpdatedAt(),
                items, invoice, buyerCompany, supplierCompany, createdBy, approvedBy, contract);
    }
}
