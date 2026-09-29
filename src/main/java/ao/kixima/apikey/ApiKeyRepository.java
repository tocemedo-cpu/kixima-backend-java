package ao.kixima.apikey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {

    List<ApiKey> findByCompanyIdOrderByCreatedAtDesc(String companyId);

    Optional<ApiKey> findByIdAndCompanyId(String id, String companyId);

    Optional<ApiKey> findByPrefixo(String prefixo);

    long countByCompanyIdAndRevogadaEmIsNull(String companyId);
}
