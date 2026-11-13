package ao.kixima.porobo.dto;

import java.time.Instant;

/**
 * Contrato {@code Actividade} do frontend, lido de `audit_logs`. {@code autor == null} significa o próprio robot.
 * {@code tipo} é um de REGRA_CRIADA, REGRA_ALTERADA, REGRA_ACTIVADA, REGRA_DESACTIVADA, REGRA_REMOVIDA, EXECUCAO,
 * PROPOSTA_CRIADA, PROPOSTA_AJUSTADA, PROPOSTA_SUBMETIDA, PROPOSTA_REJEITADA, PROPOSTA_EXPIRADA.
 */
public record PoRoboActividadeDto(String id, Instant em, String tipo, String descricao, PoRoboRegraDto.Ref autor,
                                  String referencia) {
}
