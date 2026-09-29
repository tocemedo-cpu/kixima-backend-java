package ao.kixima.discount.dto;

import java.math.BigDecimal;

public record CreateDiscountThresholdRequest(BigDecimal minVolumeUsd, BigDecimal discountPercent, Boolean ativo) {
}
