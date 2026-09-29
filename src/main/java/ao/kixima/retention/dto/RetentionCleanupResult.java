package ao.kixima.retention.dto;

import java.time.Instant;

/** Espelha o retorno de retencaoService.limpar(). */
public record RetentionCleanupResult(int notificacoes, int convites, int codigos2fa, int total, Instant corridoEm) {
}
