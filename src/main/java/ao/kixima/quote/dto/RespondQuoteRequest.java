package ao.kixima.quote.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/** Espelha respondQuoteSchema (zod): price positivo, leadDays inteiro não-negativo opcional, note opcional. */
public record RespondQuoteRequest(@NotNull @Positive BigDecimal price, @PositiveOrZero Integer leadDays, String note) {
}
