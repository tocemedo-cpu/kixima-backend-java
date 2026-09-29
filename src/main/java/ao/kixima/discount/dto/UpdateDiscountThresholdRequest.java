package ao.kixima.discount.dto;

import java.math.BigDecimal;

/** Todos os campos opcionais — um campo ausente não altera o valor já guardado. */
public record UpdateDiscountThresholdRequest(BigDecimal minVolumeUsd, BigDecimal discountPercent, Boolean ativo) {
}
