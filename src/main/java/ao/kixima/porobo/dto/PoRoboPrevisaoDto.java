package ao.kixima.porobo.dto;

import ao.kixima.porobo.PoRoboOrigemDados;
import ao.kixima.porobo.PoRoboPrevisao;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Contrato {@code Previsao} do frontend. {@code stockActual} é sempre null (sem fonte fiável do stock do comprador). */
public record PoRoboPrevisaoDto(String id, String regraId, String productId, PoRoboRegraDto.ProductRef product,
                                Instant geradaEm, String execucaoId, BigDecimal consumoMedioMensal, Integer stockActual,
                                Integer stockMinimo, Integer stockSeguranca, Integer prazoEntregaDias,
                                BigDecimal pontoEncomenda, int quantidadeRecomendada, PoRoboOrigemDados origemDados,
                                List<PoRoboPassoCalculo> justificacao) {

    public static PoRoboPrevisaoDto de(PoRoboPrevisao p, List<PoRoboPassoCalculo> justificacao) {
        return new PoRoboPrevisaoDto(p.getId(), p.getRegraId(), p.getProductId(), PoRoboRegraDto.ProductRef.de(p.getProduct()),
                p.getGeradaEm(), p.getExecucaoId(), p.getConsumoMedioMensal(), p.getStockActual(), p.getStockMinimo(),
                p.getStockSeguranca(), p.getPrazoEntregaDias(), p.getPontoEncomenda(), p.getQuantidadeRecomendada(),
                p.getOrigemDados(), justificacao);
    }
}
