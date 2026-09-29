package ao.kixima.po;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, String>, JpaSpecificationExecutor<PurchaseOrder> {

    /** Espelha `OR: [{ buyerCompanyId: companyId }, { supplierCompanyId: companyId }]` — feedbackService.opcoes. */
    @Query("SELECT po FROM PurchaseOrder po JOIN FETCH po.buyerCompany JOIN FETCH po.supplierCompany "
            + "WHERE po.buyerCompanyId = :companyId OR po.supplierCompanyId = :companyId ORDER BY po.createdAt DESC")
    List<PurchaseOrder> findByCompanyIdOrderByCreatedAtDesc(@Param("companyId") String companyId, Pageable pageable);

    /** Confirma que a empresa participou de alguma PO com a contraparte indicada — feedbackService.resolverAlvo(FORNECEDOR). */
    @Query("SELECT po FROM PurchaseOrder po JOIN FETCH po.buyerCompany JOIN FETCH po.supplierCompany "
            + "WHERE (po.buyerCompanyId = :companyId AND po.supplierCompanyId = :contraparteId) "
            + "OR (po.supplierCompanyId = :companyId AND po.buyerCompanyId = :contraparteId)")
    List<PurchaseOrder> findEntreEmpresas(@Param("companyId") String companyId, @Param("contraparteId") String contraparteId, Pageable pageable);

    /** Call-offs de um contrato ainda por faturar (consolidateContractBilling). */
    List<PurchaseOrder> findByContractIdAndIsCallOffTrueAndStatusInAndConsolidatedInvoiceIdIsNull(String contractId, Collection<PoStatus> statuses);

    /** `include: { callOffs: { orderBy: { createdAt: 'desc' } } }` de getContract. */
    List<PurchaseOrder> findByContractIdOrderByCreatedAtDesc(String contractId);

    /** Reivindicação atómica de estado (callbacks ERP concorrentes): bloqueia a linha até ao fim da transação. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT po FROM PurchaseOrder po WHERE po.id = :id")
    java.util.Optional<PurchaseOrder> findByIdParaAtualizar(@Param("id") String id);

    // dadosPessoaisService.exportar / profileService.getProfile
    List<PurchaseOrder> findByCreatedByIdOrderByCreatedAtDesc(String createdById);

    List<PurchaseOrder> findByApprovedByIdOrderByApprovedAtDesc(String approvedById);

    List<PurchaseOrder> findByBuyerCompanyIdOrderByUpdatedAtDesc(String buyerCompanyId);

    List<PurchaseOrder> findBySupplierCompanyIdOrderByUpdatedAtDesc(String supplierCompanyId);

    // --- painéis (companyAdminService / buyerService / dashboardService / publicStatsService) ---

    /** POs que envolvem a empresa (compradora ou fornecedora), sem paginação. */
    @Query("SELECT po FROM PurchaseOrder po WHERE po.buyerCompanyId = :companyId OR po.supplierCompanyId = :companyId ORDER BY po.updatedAt DESC")
    List<PurchaseOrder> findEnvolvendoEmpresaOrderByUpdatedAtDesc(@Param("companyId") String companyId);

    List<PurchaseOrder> findByBuyerCompanyIdOrderByCreatedAtDesc(String buyerCompanyId);

    List<PurchaseOrder> findByBuyerCompanyIdAndStatusInOrderByCreatedAtDesc(String buyerCompanyId, Collection<PoStatus> statuses);

    List<PurchaseOrder> findByBuyerCompanyIdAndStatusInOrderByUpdatedAtDesc(String buyerCompanyId, Collection<PoStatus> statuses);

    List<PurchaseOrder> findFirstBySupplierCompanyIdAndBuyerCompanyIdOrderByCreatedAtDesc(String supplierCompanyId, String buyerCompanyId, Pageable pageable);

    long countByStatus(PoStatus status);

    /** metricasService.volumeTransacionado — `aggregate({ _sum: totalAmount, _count })`. */
    @Query("SELECT COUNT(po), COALESCE(SUM(po.totalAmount), 0) FROM PurchaseOrder po "
            + "WHERE po.createdAt BETWEEN :de AND :ate AND po.status IN :status")
    List<Object[]> agregadoVolume(@Param("de") java.time.Instant de, @Param("ate") java.time.Instant ate, @Param("status") Collection<PoStatus> status);

    @Query("SELECT po FROM PurchaseOrder po LEFT JOIN FETCH po.buyerCompany LEFT JOIN FETCH po.supplierCompany ORDER BY po.updatedAt DESC")
    List<PurchaseOrder> findRecentesComEmpresas(Pageable pageable);

    /** conteudoLocalService.gerar — compras com compromisso real, num período. */
    List<PurchaseOrder> findByBuyerCompanyIdAndStatusInAndCreatedAtBetweenOrderByCreatedAtAsc(String buyerCompanyId, Collection<PoStatus> statuses,
                                                                                              java.time.Instant de, java.time.Instant ate);

    // reportsService.supplierStats — ordens do fornecedor (com ou sem janela; dois métodos porque o Postgres
    // não tipa um parâmetro `:desde IS NULL` sobre timestamp).
    List<PurchaseOrder> findBySupplierCompanyId(String supplierCompanyId);

    List<PurchaseOrder> findBySupplierCompanyIdAndCreatedAtGreaterThanEqual(String supplierCompanyId, java.time.Instant desde);
}
