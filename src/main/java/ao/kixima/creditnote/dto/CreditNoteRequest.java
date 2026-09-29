package ao.kixima.creditnote.dto;

import java.math.BigDecimal;

/** Corpo de POST /api/payments/invoices/{id}/notas-credito (e de /anular, onde só `motivo` conta). */
public record CreditNoteRequest(String motivo, BigDecimal amount) {
}
