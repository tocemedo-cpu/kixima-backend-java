package ao.kixima.company;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompanyRepository extends JpaRepository<Company, String> {
    boolean existsByTaxId(String taxId);

    /** Espelha o `findMany({ where: { planoValidoAte: { not: null } } })` de assinaturaService.enviarAvisosDeExpiracao. */
    List<Company> findByPlanoValidoAteIsNotNull();

    // listCompanies — filtros opcionais por estado/tipo (enums ligados como parâmetros).
    List<Company> findAllByOrderByCreatedAtDesc();

    List<Company> findByStatusOrderByCreatedAtDesc(CompanyStatus status);

    List<Company> findByTypeOrderByCreatedAtDesc(CompanyType type);

    List<Company> findByStatusAndTypeOrderByCreatedAtDesc(CompanyStatus status, CompanyType type);

    // publicStatsService / buyerService.suppliers
    List<Company> findTop10ByOrderByCreatedAtDesc();

    long countByType(CompanyType type);

    long countByTypeAndSerieFiscalIsNotNull(CompanyType type);

    long countByStatus(CompanyStatus status);

    long countByStatusAndType(CompanyStatus status, CompanyType type);

    List<Company> findByTypeAndStatusInOrderByCreatedAtDesc(CompanyType type, java.util.Collection<CompanyStatus> statuses);

    List<Company> findByTypeAndStatusInAndNameContainingIgnoreCaseOrderByCreatedAtDesc(CompanyType type, java.util.Collection<CompanyStatus> statuses, String q);
}
