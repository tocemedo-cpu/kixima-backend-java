package ao.kixima.porobo.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** Corpo de PUT /api/po-robot/propostas/{id}: só a nova quantidade (JsonNode para rejeitar 2.5 e "3" em vez de os arredondar). */
public record PoRoboAjusteRequest(JsonNode quantidade) {
}
