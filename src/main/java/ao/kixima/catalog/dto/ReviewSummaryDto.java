package ao.kixima.catalog.dto;

/** Espelha o retorno de reviewService.addReview — média (arredondada a 1 casa) + contagem recalculadas. */
public record ReviewSummaryDto(Float rating, int reviewCount) {
}
