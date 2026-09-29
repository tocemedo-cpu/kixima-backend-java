package ao.kixima.invoice.dto;

import ao.kixima.contract.dto.ContractDto;
import ao.kixima.creditnote.dto.CreditNoteDto;
import ao.kixima.invoice.Invoice;
import ao.kixima.invoice.InvoiceStatus;
import ao.kixima.payment.dto.PaymentDto;
import ao.kixima.po.dto.PurchaseOrderDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Espelha a linha `Invoice` tal como o Prisma a devolve — todos os escalares,
 * {@code null} incluído — mais as relações que cada `include` traz:
 * {@code purchaseOrder} (linha escalar), {@code contract}, {@code payment},
 * {@code creditNotes} (por issuedAt) e {@code lines} (por lineNumber).
 * As relações ausentes são omitidas, como o Prisma faz.
 */
public record InvoiceDto(String id, String reference, String purchaseOrderId, BigDecimal amount, BigDecimal netAmount,
                         BigDecimal taxAmount, BigDecimal withholdingAmount, String currency, InvoiceStatus status,
                         Instant issuedAt, Instant dueAt, String serie, Integer numeroNaSerie, String hashDocumento, String hashAnterior,
                         Instant assinadaEm, String referenciaPagamento, String agtDocumentNo, String agtRequestId, String agtResultCode,
                         JsonNode agtErro, JsonNode agtEstado, String contractId, List<String> consolidatedPoIds,
                         Instant createdAt, Instant updatedAt,
                         @JsonInclude(JsonInclude.Include.NON_NULL) PurchaseOrderDto purchaseOrder,
                         @JsonInclude(JsonInclude.Include.NON_NULL) ContractDto contract,
                         @JsonInclude(JsonInclude.Include.NON_NULL) PaymentDto payment,
                         @JsonInclude(JsonInclude.Include.NON_NULL) List<CreditNoteDto> creditNotes,
                         @JsonInclude(JsonInclude.Include.NON_NULL) List<InvoiceLineDto> lines) {

    /** Só para ler as colunas JSON (agt_erro/agt_estado) como o Prisma as devolve: objectos, não texto. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Linha + {@code purchaseOrder} (escalar) e {@code contract} quando o include os traz
     * (`include: { purchaseOrder: true, contract: true }` — na fatura consolidada de call-offs
     * só o contrato existe); sem pagamento, notas ou linhas.
     */
    public static InvoiceDto de(Invoice i, boolean comPurchaseOrder) {
        PurchaseOrderDto po = comPurchaseOrder && i.getPurchaseOrder() != null ? PurchaseOrderDto.escalar(i.getPurchaseOrder()) : null;
        ContractDto contract = comPurchaseOrder && i.getContract() != null ? ContractDto.de(i.getContract(), false, null) : null;
        return de(i, po, contract, null, null, null);
    }

    public static InvoiceDto de(Invoice i, PurchaseOrderDto purchaseOrder, ContractDto contract, PaymentDto payment,
                                List<CreditNoteDto> creditNotes, List<InvoiceLineDto> lines) {
        return new InvoiceDto(i.getId(), i.getReference(), i.getPurchaseOrderId(), i.getAmount(), i.getNetAmount(), i.getTaxAmount(),
                i.getWithholdingAmount(), i.getCurrency(), i.getStatus(), i.getIssuedAt(), i.getDueAt(), i.getSerie(),
                i.getNumeroNaSerie(), i.getHashDocumento(), i.getHashAnterior(), i.getAssinadaEm(), i.getReferenciaPagamento(),
                i.getAgtDocumentNo(), i.getAgtRequestId(), i.getAgtResultCode(), json(i.getAgtErro()), json(i.getAgtEstado()),
                i.getContractId(), i.getConsolidatedPoIds(), i.getCreatedAt(), i.getUpdatedAt(),
                purchaseOrder, contract, payment, creditNotes, lines);
    }

    private static JsonNode json(String raw) {
        if (raw == null) return null;
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
