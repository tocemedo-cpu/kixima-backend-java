package ao.kixima.creditnote.dto;

import ao.kixima.creditnote.CreditNote;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;

/** Espelha a linha `CreditNote` devolvida pelo Node — todos os campos, `null` incluído. */
public record CreditNoteDto(String id, String reference, String invoiceId, String motivo, BigDecimal amount,
                            BigDecimal netAmount, BigDecimal taxAmount, String currency, String serie, Integer numeroNaSerie,
                            String hashDocumento, String hashAnterior, Instant assinadaEm, String agtDocumentNo,
                            String agtRequestId, String agtResultCode, JsonNode agtErro, JsonNode agtEstado,
                            Instant issuedAt, String createdById, Instant createdAt) {

    public static CreditNoteDto de(CreditNote c, ObjectMapper objectMapper) {
        return new CreditNoteDto(c.getId(), c.getReference(), c.getInvoiceId(), c.getMotivo(), c.getAmount(), c.getNetAmount(),
                c.getTaxAmount(), c.getCurrency(), c.getSerie(), c.getNumeroNaSerie(), c.getHashDocumento(), c.getHashAnterior(),
                c.getAssinadaEm(), c.getAgtDocumentNo(), c.getAgtRequestId(), c.getAgtResultCode(),
                json(objectMapper, c.getAgtErro()), json(objectMapper, c.getAgtEstado()), c.getIssuedAt(), c.getCreatedById(),
                c.getCreatedAt());
    }

    private static JsonNode json(ObjectMapper om, String raw) {
        if (raw == null) return null;
        try {
            return om.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
