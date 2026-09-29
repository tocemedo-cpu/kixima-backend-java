package ao.kixima.cobranca;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AddonCobrancaRepository extends JpaRepository<AddonCobranca, String> {

    List<AddonCobranca> findByCompanyIdAndAddonKeyAndStatusInOrderByCreatedAtDesc(String companyId, String addonKey,
                                                                                  Collection<CobrancaStatus> statuses, Pageable pageable);

    List<AddonCobranca> findByStatusInOrderByStatusDescCreatedAtAsc(Collection<CobrancaStatus> statuses);
}
