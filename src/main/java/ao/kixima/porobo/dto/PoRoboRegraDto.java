package ao.kixima.porobo.dto;

import ao.kixima.catalog.Product;
import ao.kixima.porobo.PoRoboMediaOrigem;
import ao.kixima.porobo.PoRoboPeriodicidade;
import ao.kixima.porobo.PoRoboRegra;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Espelha a linha `PoRoboRegra` devolvida por poRoboRoutes.js — todos os
 * escalares, {@code null} incluído ({@code quantidade: null} = sem quantidade
 * fixa) — com `product` (e a fornecedora) quando a regra é carregada com o produto.
 * Os campos de stock/prazo e {@code ultimaExecucaoEm} chegam com a V4.
 */
public record PoRoboRegraDto(String id, String companyId, String productId, PoRoboMediaOrigem mediaOrigem,
                             BigDecimal mediaMensal, PoRoboPeriodicidade periodicidade, Integer quantidade,
                             boolean ativo, BigDecimal limiteMaximoUsd, Integer stockMinimo, Integer stockSeguranca,
                             Integer prazoEntregaDias, Instant proximaExecucaoEm, Instant ultimaExecucaoEm,
                             Instant createdAt, Instant updatedAt,
                             @JsonInclude(JsonInclude.Include.NON_NULL) ProductRef product) {

    /** {id, name} — fornecedora de um produto, autor de uma decisão ou de uma actividade. */
    public record Ref(String id, String name) {
    }

    /** Resumo do produto (contrato {@code ProdutoResumo} do frontend). */
    public record ProductRef(String id, String name, String sku, BigDecimal unitPrice, String currency,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Ref supplier) {

        public static ProductRef de(Product p) {
            if (p == null) return null;
            Ref fornecedora = p.getSupplier() == null ? null : new Ref(p.getSupplier().getId(), p.getSupplier().getName());
            return new ProductRef(p.getId(), p.getName(), p.getSku(), p.getUnitPrice(), p.getCurrency(), fornecedora);
        }
    }

    public static PoRoboRegraDto from(PoRoboRegra r, boolean comProduto) {
        return from(r, comProduto ? ProductRef.de(r.getProduct()) : null);
    }

    /** Para regras acabadas de gravar, cuja relação {@code product} (só de leitura) ainda não foi carregada. */
    public static PoRoboRegraDto from(PoRoboRegra r, ProductRef product) {
        return new PoRoboRegraDto(r.getId(), r.getCompanyId(), r.getProductId(), r.getMediaOrigem(), r.getMediaMensal(),
                r.getPeriodicidade(), r.getQuantidade(), r.isAtivo(), r.getLimiteMaximoUsd(), r.getStockMinimo(),
                r.getStockSeguranca(), r.getPrazoEntregaDias(), r.getProximaExecucaoEm(), r.getUltimaExecucaoEm(),
                r.getCreatedAt(), r.getUpdatedAt(), product);
    }
}
