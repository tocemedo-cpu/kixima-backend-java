package ao.kixima.porobo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * Corpo de POST/PUT /api/po-robot/regras. Os campos são {@code JsonNode}
 * onde o Node distingue "ausente" de "null" ({@code quantidade}/
 * {@code limiteMaximoUsd} — {@code !== undefined}): null explícito limpa,
 * ausente mantém. Validação em PoRoboService.validarCorpo, tal como no Node.
 */
public record PoRoboRegraRequest(String productId, String mediaOrigem, BigDecimal mediaMensal, String periodicidade,
                                 JsonNode quantidade, JsonNode limiteMaximoUsd, Boolean ativo) {
}
