package ao.kixima.porobo.dto;

import ao.kixima.analytics.CategoryAnalyticsService;

import java.math.BigDecimal;

/**
 * Resposta de GET /api/po-robot/media-sugerida/{productId}. Mantém os campos que já existiam
 * ({@code produtoId}, {@code amostras}, {@code mesesComCompra}, {@code mesesDecorridos}, {@code quantidadeTotal},
 * {@code mediaMensal}) e acrescenta os do contrato do frontend: {@code productId} e {@code periodoMeses}.
 */
public record PoRoboMediaSugeridaDto(String productId, String produtoId, int amostras, int mesesComCompra,
                                     Integer mesesDecorridos, Long quantidadeTotal, BigDecimal mediaMensal,
                                     int periodoMeses) {

    public static PoRoboMediaSugeridaDto de(CategoryAnalyticsService.MediaMensal m, int periodoMeses) {
        return new PoRoboMediaSugeridaDto(m.produtoId(), m.produtoId(), m.amostras(), m.mesesComCompra(),
                m.mesesDecorridos(), m.quantidadeTotal(), m.mediaMensal(), periodoMeses);
    }
}
