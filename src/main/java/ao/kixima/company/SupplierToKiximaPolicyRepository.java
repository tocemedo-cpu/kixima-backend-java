package ao.kixima.company;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierToKiximaPolicyRepository extends JpaRepository<SupplierToKiximaPolicy, String> {

    List<SupplierToKiximaPolicy> findByCompanyId(String companyId);

    List<SupplierToKiximaPolicy> findTop10ByOrderByCreatedAtDesc();

    long countByDocumentUrlStartingWith(String prefixo);

    long countByCompanyId(String companyId);
}
