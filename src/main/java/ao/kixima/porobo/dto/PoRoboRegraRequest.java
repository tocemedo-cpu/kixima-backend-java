package ao.kixima.porobo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * Corpo de POST/PUT /api/po-robot/regras. Os campos são {@code JsonNode}
 * onde o Node distingue "ausente" de "null" ({@code quantidade}/
 * {@code limiteMaximoUsd}/stocks/prazo — {@code !== undefined}): null explícito limpa,
 * ausente mantém. Validação em PoRoboService.validarCorpo, tal como no Node.
 */
public record PoRoboRegraRequest(String productId, String mediaOrigem, BigDecimal mediaMensal, String periodicidade,
                                 JsonNode quantidade, JsonNode limiteMaximoUsd, Boolean ativo,
                                 JsonNode stockMinimo, JsonNode stockSeguranca, JsonNode prazoEntregaDias) {

    /** Corpo sem os campos de stock/prazo (V4) — o que o contrato antigo enviava. */
    public PoRoboRegraRequest(String productId, String mediaOrigem, BigDecimal mediaMensal, String periodicidade,
                              JsonNode quantidade, JsonNode limiteMaximoUsd, Boolean ativo) {
        this(productId, mediaOrigem, mediaMensal, periodicidade, quantidade, limiteMaximoUsd, ativo, null, null, null);
    }
}
