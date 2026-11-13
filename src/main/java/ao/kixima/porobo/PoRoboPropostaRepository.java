package ao.kixima.porobo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PoRoboPropostaRepository extends JpaRepository<PoRoboProposta, String> {

    /** Só da empresa pedida — o isolamento entre empresas vive na consulta, nunca no chamador. */
    @Query("SELECT p FROM PoRoboProposta p JOIN FETCH p.product pr JOIN FETCH pr.supplier WHERE p.companyId = :companyId ORDER BY p.criadaEm DESC")
    List<PoRoboProposta> listarDaEmpresa(@Param("companyId") String companyId);

    Optional<PoRoboProposta> findByIdAndCompanyId(String id, String companyId);

    List<PoRoboProposta> findByRegraIdAndEstado(String regraId, EstadoProposta estado);

    /**
     * Reserva atómica da decisão humana: só passa a {@code estado} se ainda estiver pendente. Duas submissões
     * simultâneas da mesma proposta nunca criam duas POs — a segunda espera pelo bloqueio da linha e afecta zero linhas.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PoRoboProposta p SET p.estado = :novo WHERE p.id = :id AND p.companyId = :companyId AND p.estado = ao.kixima.porobo.EstadoProposta.PENDENTE_REVISAO")
    int reservarDecisao(@Param("id") String id, @Param("companyId") String companyId, @Param("novo") EstadoProposta novo);
}
