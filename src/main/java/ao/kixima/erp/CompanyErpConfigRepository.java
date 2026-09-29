package ao.kixima.erp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyErpConfigRepository extends JpaRepository<CompanyErpConfig, String> {
    Optional<CompanyErpConfig> findByCompanyId(String companyId);
}
