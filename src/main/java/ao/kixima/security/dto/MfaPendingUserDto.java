package ao.kixima.security.dto;

import java.time.Instant;

/** Espelha o item devolvido por mfaLembreteService.pendentes. */
public record MfaPendingUserDto(String id, String nome, String email, String perfil, String empresa,
                                 Instant criadaEm, Instant ultimoLogin, Instant ultimoLembrete) {
}
