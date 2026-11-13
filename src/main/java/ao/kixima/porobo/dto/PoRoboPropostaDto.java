package ao.kixima.porobo.dto;

import ao.kixima.porobo.EstadoProposta;
import ao.kixima.porobo.PoRoboProposta;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Contrato {@code Proposta} do frontend. {@code quantidade} é sempre &gt; 0; {@code quantidadeOriginal} é o que o robot
 * calculou antes do limite de segurança ou de um ajuste. {@code purchaseOrderId}/{@code purchaseOrderReference} só existem
 * depois de uma pessoa submeter a proposta.
 */
public record PoRoboPropostaDto(String id, String regraId, String previsaoId, String execucaoId, String productId,
                                PoRoboRegraDto.ProductRef product, PoRoboRegraDto.Ref supplier, int quantidade,
                                int quantidadeOriginal, BigDecimal precoUnitario, String moeda, BigDecimal valorTotal,
                                EstadoProposta estado, Instant criadaEm, Instant expiradaEm, String motivoExpiracao,
                                List<PoRoboPassoCalculo> justificacao, boolean limiteAplicado, String purchaseOrderId,
                                String purchaseOrderReference, String motivoRejeicao, PoRoboRegraDto.Ref revistaPor) {

    public static PoRoboPropostaDto de(PoRoboProposta p, List<PoRoboPassoCalculo> justificacao, PoRoboRegraDto.Ref revistaPor) {
        PoRoboRegraDto.ProductRef produto = PoRoboRegraDto.ProductRef.de(p.getProduct());
        return new PoRoboPropostaDto(p.getId(), p.getRegraId(), p.getPrevisaoId(), p.getExecucaoId(), p.getProductId(),
                produto, produto == null ? null : produto.supplier(), p.getQuantidade(), p.getQuantidadeOriginal(),
                p.getPrecoUnitario(), p.getMoeda(), p.getValorTotal(), p.getEstado(), p.getCriadaEm(), p.getExpiradaEm(),
                p.getMotivoExpiracao(), justificacao, p.isLimiteAplicado(), p.getPurchaseOrderId(),
                p.getPurchaseOrderReference(), p.getMotivoRejeicao(), revistaPor);
    }
}
