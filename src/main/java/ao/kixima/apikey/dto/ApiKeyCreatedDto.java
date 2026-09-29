package ao.kixima.apikey.dto;

import java.time.Instant;

/** Espelha o retorno de apiKeyService.criar — a ÚNICA vez que `chave` (o segredo inteiro) sai da API. */
public record ApiKeyCreatedDto(String id, String nome, String prefixo, Instant createdAt, String chave, String aviso) {
}
