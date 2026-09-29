package ao.kixima.invoice.dto;

import ao.kixima.invoice.InvoiceLine;

import java.math.BigDecimal;
import java.time.Instant;

/** Espelha a linha `InvoiceLine` devolvida pelo Node (include `lines`, ordenadas por `lineNumber`). */
public record InvoiceLineDto(String id, String invoiceId, int lineNumber, String productCode, String description,
                             BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount, BigDecimal netAmount,
                             BigDecimal ivaAmount, BigDecimal iecAmount, BigDecimal isAmount, String ivaTaxCode, Instant createdAt) {

    public static InvoiceLineDto de(InvoiceLine l) {
        return new InvoiceLineDto(l.getId(), l.getInvoiceId(), l.getLineNumber(), l.getProductCode(), l.getDescription(),
                l.getQuantity(), l.getUnitPrice(), l.getDiscount(), l.getNetAmount(), l.getIvaAmount(), l.getIecAmount(),
                l.getIsAmount(), l.getIvaTaxCode(), l.getCreatedAt());
    }
}
