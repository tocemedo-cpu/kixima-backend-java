package ao.kixima.po.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record CreatePoRequest(@NotBlank String supplierCompanyId, @NotEmpty @Valid List<Item> items) {
    public record Item(@NotBlank String productId, @Positive int quantity) {
    }
}
