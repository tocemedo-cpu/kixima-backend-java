package ao.kixima.porobo.dto;

/** Corpo de POST /api/po-robot/propostas/{id}/rejeitar: o motivo é obrigatório. */
public record PoRoboRejeicaoRequest(String motivo) {
}
