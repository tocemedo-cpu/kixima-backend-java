package ao.kixima.agt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgtSeriesFeRepository extends JpaRepository<AgtSeriesFe, String> {

    List<AgtSeriesFe> findAllByOrderByCreatedAtDesc();

    Optional<AgtSeriesFe> findFirstByTipoDocumentoAndAnoAndEstablishmentNumberOrderByCreatedAtDesc(
            String tipoDocumento, int ano, String establishmentNumber);
}
