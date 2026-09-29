package ao.kixima.conciliacao;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LinhaExtratoRepository extends JpaRepository<LinhaExtrato, String> {

    Optional<LinhaExtrato> findByIdNoBanco(String idNoBanco);

    /** As linhas por resolver, com a fatura quando há — `porResolver()`. */
    @Query(value = "SELECT l FROM LinhaExtrato l LEFT JOIN FETCH l.invoice WHERE l.estado IN :estados ORDER BY l.dataValor DESC",
            countQuery = "SELECT count(l) FROM LinhaExtrato l WHERE l.estado IN :estados")
    Page<LinhaExtrato> findPorResolver(@Param("estados") List<String> estados, Pageable pageable);

    long countByImportadaEmBetweenAndEstado(java.time.Instant de, java.time.Instant ate, String estado);

    long countByImportadaEmBetweenAndEstadoIn(java.time.Instant de, java.time.Instant ate, List<String> estados);
}
