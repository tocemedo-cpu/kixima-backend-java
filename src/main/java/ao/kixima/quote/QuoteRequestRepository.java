package ao.kixima.quote;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface QuoteRequestRepository extends JpaRepository<QuoteRequest, String> {

    /** Cotações pedidas pela empresa no mês corrente — a medida de intensidade de uso que o plano limita. */
    long countByBuyerCompanyIdAndCreatedAtGreaterThanEqual(String buyerCompanyId, Instant inicioDoMes);

    long countByCreatedAtBetween(Instant de, Instant ate);

    long countByCreatedAtBetweenAndStatusIn(Instant de, Instant ate, java.util.Collection<QuoteStatus> statuses);

    // `listForBuyer` / `listForSupplier`: dois métodos por lado porque o Postgres não consegue tipar
    // um parâmetro `:status IS NULL` sobre um enum nomeado ("could not determine data type").
    List<QuoteRequest> findByBuyerCompanyIdOrderByCreatedAtDesc(String buyerCompanyId);

    List<QuoteRequest> findByBuyerCompanyIdAndStatusOrderByCreatedAtDesc(String buyerCompanyId, QuoteStatus status);

    List<QuoteRequest> findBySupplierCompanyIdOrderByCreatedAtDesc(String supplierCompanyId);

    List<QuoteRequest> findBySupplierCompanyIdAndStatusOrderByCreatedAtDesc(String supplierCompanyId, QuoteStatus status);
}
