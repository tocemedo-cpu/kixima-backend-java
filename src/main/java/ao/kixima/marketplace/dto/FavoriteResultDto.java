package ao.kixima.marketplace.dto;

/** Espelha o retorno de favoriteService.add/remove — `{ productId, favorite }`. */
public record FavoriteResultDto(String productId, boolean favorite) {
}
