package ao.kixima.creditnote;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CreditNoteRepository extends JpaRepository<CreditNote, String> {

    List<CreditNote> findByInvoiceIdOrderByIssuedAtAsc(String invoiceId);

    /** Soma de todas as notas de crédito já emitidas para esta fatura — o saldo por creditar é `amount - isto`. */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM CreditNote c WHERE c.invoiceId = :invoiceId")
    BigDecimal totalCreditado(@Param("invoiceId") String invoiceId);

    @Query("SELECT c FROM CreditNote c JOIN FETCH c.invoice i JOIN FETCH i.purchaseOrder WHERE c.id = :id")
    Optional<CreditNote> findByIdComFatura(@Param("id") String id);

    /** saftService.gerar — notas de crédito do período cujas faturas são de UM fornecedor. */
    @Query("SELECT n FROM CreditNote n JOIN FETCH n.invoice i LEFT JOIN PurchaseOrder po ON po.id = i.purchaseOrderId "
            + "LEFT JOIN Contract c ON c.id = i.contractId WHERE n.issuedAt BETWEEN :ini AND :fim "
            + "AND (po.supplierCompanyId = :sid OR c.supplierCompanyId = :sid) "
            + "ORDER BY n.serie ASC, n.numeroNaSerie ASC, n.issuedAt ASC")
    List<CreditNote> findDoFornecedorNoPeriodo(@org.springframework.data.repository.query.Param("sid") String supplierCompanyId,
                                               @org.springframework.data.repository.query.Param("ini") java.time.Instant ini,
                                               @org.springframework.data.repository.query.Param("fim") java.time.Instant fim);

    List<CreditNote> findByInvoiceIdIn(java.util.Collection<String> invoiceIds);
}
