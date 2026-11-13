package ao.kixima.porobo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PoRoboExecucaoRepository extends JpaRepository<PoRoboExecucao, String> {

    List<PoRoboExecucao> findByCompanyIdOrderByIniciadaEmDesc(String companyId, Pageable pageable);
}
