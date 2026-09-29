package ao.kixima.discount.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record DiscountThresholdDto(String id, BigDecimal minVolumeUsd, BigDecimal discountPercent, boolean ativo,
                                    Instant createdAt, Instant updatedAt) {
}
