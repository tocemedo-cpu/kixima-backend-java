package ao.kixima.erp;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompanyErpConfigAuditRepository extends JpaRepository<CompanyErpConfigAudit, String> {
    List<CompanyErpConfigAudit> findByCompanyIdOrderByCreatedAtDesc(String companyId, Pageable pageable);
}
