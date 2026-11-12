package ao.kixima.banner.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Um banner na homepage pública: só o que o carrossel mostra. Nunca {@code ativo}, {@code ordem} nem
 * {@code atualizadoEm} (são de gestão).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicBannerDto(
        String id,
        String etiqueta,
        String titulo,
        String texto,
        String cta,
        String href,
        String cta2,
        String href2,
        String imagem
) {
}
