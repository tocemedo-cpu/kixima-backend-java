package ao.kixima.supplierdev.dto;

import java.math.BigDecimal;

/** Espelha supplierDevUpdateSchema — CONCLUIDA fica de fora de propósito, ver {@code approve}. */
public record UpdateSupplierDevRequest(String status, String adminNotes, String feeStatus, BigDecimal programFeeUsd) {
}
