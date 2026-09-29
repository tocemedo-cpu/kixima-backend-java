package ao.kixima.payment.dto;

import ao.kixima.invoice.dto.InvoiceDto;
import ao.kixima.payment.CanalPagamento;
import ao.kixima.payment.Payment;
import ao.kixima.payment.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Espelha a linha `Payment` devolvida pelo Node: {@code invoice} vem no histórico
 * (`include`), {@code agtInvoiceResubmission} vem só na resposta do pagamento —
 * os restantes campos vêm sempre, {@code null} incluído ({@code receivedAt: null}
 * é informação: "ainda não confirmado").
 */
public record PaymentDto(String id, String invoiceId, BigDecimal amount, String currency, PaymentStatus status,
                         CanalPagamento canal, String processedById, String reference, String proofUrl, String proofName,
                         Instant receivedAt, String receivedById, Instant processedAt, String serie, Integer numeroNaSerie,
                         String hashDocumento, String hashAnterior, Instant assinadaEm, String agtDocumentNo,
                         @JsonInclude(JsonInclude.Include.NON_NULL) InvoiceDto invoice,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Object agtInvoiceResubmission,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String processedByName) {

    public static PaymentDto de(Payment p, InvoiceDto invoice, Object agtInvoiceResubmission) {
        return de(p, invoice, agtInvoiceResubmission, null);
    }

    /** Detalhe da PO: o Node acrescenta {@code processedByName} ao pagamento (Payment só guarda o id). */
    public static PaymentDto de(Payment p, InvoiceDto invoice, Object agtInvoiceResubmission, String processedByName) {
        return new PaymentDto(p.getId(), p.getInvoiceId(), p.getAmount(), p.getCurrency(), p.getStatus(), p.getCanal(),
                p.getProcessedById(), p.getReference(), p.getProofUrl(), p.getProofName(), p.getReceivedAt(), p.getReceivedById(),
                p.getProcessedAt(), p.getSerie(), p.getNumeroNaSerie(), p.getHashDocumento(), p.getHashAnterior(), p.getAssinadaEm(),
                p.getAgtDocumentNo(), invoice, agtInvoiceResubmission, processedByName);
    }
}
