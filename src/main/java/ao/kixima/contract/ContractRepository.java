package ao.kixima.contract;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ContractRepository extends JpaRepository<Contract, String> {

    /** `listContractsForCompany`: OR entre cliente e fornecedor. */
    @Query("SELECT c FROM Contract c WHERE c.clientCompanyId = :companyId OR c.supplierCompanyId = :companyId ORDER BY c.createdAt DESC")
    List<Contract> findDaEmpresa(@Param("companyId") String companyId);

    List<Contract> findAllByOrderByCreatedAtDesc();

    /** Candidatos a call-off: ATIVO e em vigor entre este cliente e este fornecedor (o enum é ligado como parâmetro). */
    List<Contract> findByClientCompanyIdAndSupplierCompanyIdAndStatusAndValidFromLessThanEqualAndValidUntilGreaterThanEqual(
            String clientCompanyId, String supplierCompanyId, ContractStatus status, Instant validFromAte, Instant validUntilDesde);

    /** assinaturaService.FUNCIONALIDADES_COM_USO — contratos-quadro ATIVOS em que a empresa é parte. */
    @Query("SELECT COUNT(c) FROM Contract c WHERE (c.clientCompanyId = :companyId OR c.supplierCompanyId = :companyId) AND c.status = :status")
    long countDaEmpresaComStatus(@Param("companyId") String companyId, @Param("status") ContractStatus status);
}
