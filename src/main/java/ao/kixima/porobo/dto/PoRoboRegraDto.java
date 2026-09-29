package ao.kixima.porobo.dto;

import ao.kixima.porobo.PoRoboMediaOrigem;
import ao.kixima.porobo.PoRoboPeriodicidade;
import ao.kixima.porobo.PoRoboRegra;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Espelha a linha `PoRoboRegra` devolvida por poRoboRoutes.js — todos os
 * escalares, {@code null} incluído ({@code quantidade: null} = sem quantidade
 * fixa) — com `product` só na listagem (`include`).
 */
public record PoRoboRegraDto(String id, String companyId, String productId, PoRoboMediaOrigem mediaOrigem,
                             BigDecimal mediaMensal, PoRoboPeriodicidade periodicidade, Integer quantidade,
                             boolean ativo, BigDecimal limiteMaximoUsd, Instant proximaExecucaoEm,
                             Instant createdAt, Instant updatedAt,
                             @JsonInclude(JsonInclude.Include.NON_NULL) ProductRef product) {

    public record ProductRef(String id, String name, String sku, BigDecimal unitPrice, String currency) {
    }

    public static PoRoboRegraDto from(PoRoboRegra r, boolean comProduto) {
        ProductRef product = null;
        if (comProduto && r.getProduct() != null) {
            var p = r.getProduct();
            product = new ProductRef(p.getId(), p.getName(), p.getSku(), p.getUnitPrice(), p.getCurrency());
        }
        return new PoRoboRegraDto(r.getId(), r.getCompanyId(), r.getProductId(), r.getMediaOrigem(), r.getMediaMensal(),
                r.getPeriodicidade(), r.getQuantidade(), r.isAtivo(), r.getLimiteMaximoUsd(), r.getProximaExecucaoEm(),
                r.getCreatedAt(), r.getUpdatedAt(), product);
    }
}
