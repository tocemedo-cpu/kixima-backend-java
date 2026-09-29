package ao.kixima.apikey.dto;

import java.time.Instant;

/** Espelha o retorno de apiKeyService.listar — a chave inteira NUNCA aqui. */
public record ApiKeyDto(String id, String nome, String prefixo, Instant ultimoUso, Instant revogadaEm,
                         Instant createdAt, boolean ativa) {
}
