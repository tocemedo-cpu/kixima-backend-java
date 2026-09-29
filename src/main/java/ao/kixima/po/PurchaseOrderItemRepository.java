package ao.kixima.po;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, String> {
    List<PurchaseOrderItem> findByPurchaseOrderIdOrderByIdAsc(String purchaseOrderId);

    /** Confirma que a empresa comprou/vendeu este produto — feedbackService.resolverAlvo(PRODUTO/SERVICO). */
    @Query("SELECT i FROM PurchaseOrderItem i JOIN FETCH i.product p JOIN i.purchaseOrder po "
            + "WHERE i.productId = :productId AND (po.buyerCompanyId = :companyId OR po.supplierCompanyId = :companyId)")
    List<PurchaseOrderItem> findByProductIdEComEmpresa(@Param("productId") String productId, @Param("companyId") String companyId, Pageable pageable);

    /**
     * Espelha o `findMany` de categoryAnalyticsService.historicoMensalPorProduto —
     * compra reconhecida de um produto por uma empresa num período; devolve
     * [po.createdAt, quantity] por item, agrupado por mês do lado Java.
     */
    /** categoryAnalyticsService.volumePorCategoria / oportunidadesConsolidacao — categoria, total da linha e PO. */
    @Query("SELECT p.category, i.lineTotal, i.purchaseOrderId FROM PurchaseOrderItem i LEFT JOIN i.product p JOIN i.purchaseOrder po "
            + "WHERE po.buyerCompanyId = :companyId AND po.status IN :status AND po.createdAt BETWEEN :de AND :ate")
    List<Object[]> linhasReconhecidasDaEmpresa(@Param("companyId") String companyId, @Param("status") List<PoStatus> status,
                                               @Param("de") java.time.Instant de, @Param("ate") java.time.Instant ate);

    @Query("SELECT po.createdAt, i.quantity FROM PurchaseOrderItem i JOIN i.purchaseOrder po "
            + "WHERE i.productId = :productId AND po.buyerCompanyId = :companyId AND po.status IN :status "
            + "AND po.createdAt BETWEEN :de AND :ate")
    List<Object[]> historicoDeCompra(@Param("companyId") String companyId, @Param("productId") String productId,
                                     @Param("status") List<PoStatus> status, @Param("de") java.time.Instant de,
                                     @Param("ate") java.time.Instant ate);

    List<PurchaseOrderItem> findByPurchaseOrderIdIn(java.util.Collection<String> purchaseOrderIds);
}
