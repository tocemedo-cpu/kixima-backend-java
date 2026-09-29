package ao.kixima.company;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompanyDocumentRepository extends JpaRepository<CompanyDocument, String> {
    List<CompanyDocument> findByCompanyIdOrderByTypeAsc(String companyId);

    long countByFileUrlStartingWith(String prefixo);

    long countByCompanyId(String companyId);
}
