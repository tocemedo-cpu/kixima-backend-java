package ao.kixima.cobranca;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlanoCobrancaRepository extends JpaRepository<PlanoCobranca, String> {

    /** `findFirst({ companyId, status in EM_ABERTO }, orderBy createdAt desc)` — só pode haver uma viva por empresa. */
    List<PlanoCobranca> findByCompanyIdAndStatusInOrderByCreatedAtDesc(String companyId, Collection<CobrancaStatus> statuses, Pageable pageable);

    List<PlanoCobranca> findByCompanyIdAndStatusNotInOrderByCreatedAtDesc(String companyId, Collection<CobrancaStatus> statuses, Pageable pageable);

    long countByComprovativoUrlStartingWith(String prefixo);

    /** fila(): em aberto, `orderBy: [{ status: 'desc' }, { createdAt: 'asc' }]`. */
    List<PlanoCobranca> findByStatusInOrderByStatusDescCreatedAtAsc(Collection<CobrancaStatus> statuses);

    /** Webhook: a cobrança que iniciou um pagamento neste canal com esta referência externa. */
    Optional<PlanoCobranca> findFirstByCanalAndReferenciaExterna(CanalCobranca canal, String referenciaExterna);
}
