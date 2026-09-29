package ao.kixima.catalog.dto;

/**
 * Espelha stockUpdateSchema (utils/schemas.js) — todos os campos opcionais;
 * um campo ausente (ou vazio/null, `optInt`/`optText` tratam-nos da mesma
 * forma) NÃO altera o valor já guardado, nunca o limpa.
 */
public record UpdateStockRequest(Integer stockQuantity, Integer minStock, String warehouse, String availability) {
}
