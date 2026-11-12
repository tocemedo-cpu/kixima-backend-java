package ao.kixima.banner.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Um banner como o Admin do Sistema o vê ({@code BannerAdmin} do frontend). Os textos opcionais ausentes não vão no JSON
 * (o tipo do frontend é {@code etiqueta?: string}); {@code atualizadoEm} vai sempre, {@code null} se nunca foi alterado.
 */
public record BannerDto(
        String id,
        @JsonInclude(JsonInclude.Include.NON_NULL) String etiqueta,
        String titulo,
        String texto,
        String cta,
        String href,
        @JsonInclude(JsonInclude.Include.NON_NULL) String cta2,
        @JsonInclude(JsonInclude.Include.NON_NULL) String href2,
        String imagem,
        boolean ativo,
        int ordem,
        @JsonInclude(JsonInclude.Include.ALWAYS) Instant atualizadoEm
) {
}
