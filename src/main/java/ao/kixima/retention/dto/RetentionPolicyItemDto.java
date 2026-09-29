package ao.kixima.retention.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Espelha o retorno de retencaoService.politica() — a política, para
 * publicar em {@code GET /api/retencao}. A chave JSON `o_que` (com
 * underscore) é contrato de API já publicado, mantida tal como está.
 */
public record RetentionPolicyItemDto(String id, @JsonProperty("o_que") String oQue, String prazo, Integer dias,
                                      String porque) {
}
