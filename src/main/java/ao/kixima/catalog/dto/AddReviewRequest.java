package ao.kixima.catalog.dto;

/** Espelha reviewSchema (utils/schemas.js): rating inteiro 1..5, comment opcional (máx. 1000). */
public record AddReviewRequest(Integer rating, String comment) {
}
