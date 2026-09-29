package ao.kixima.quote.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

/** Espelha createQuoteSchema (zod): supplierCompanyId uuid, note opcional, items min(1) com quantity positiva (omissão 1). */
public record CreateQuoteRequest(@NotBlank String supplierCompanyId, String note, @NotEmpty @Valid List<Item> items) {

    public record Item(@NotBlank String productId, @Positive Integer quantity) {
        public int quantidadeOuUm() {
            return quantity == null ? 1 : quantity;
        }
    }
}
