package ao.kixima.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, String>, JpaSpecificationExecutor<AuditLog> {

    /** Espelha `findFirst({ where: { action, entityRef }, orderBy: { createdAt: 'desc' } })` — alertaOperacionalService.avisadoRecentemente. */
    List<AuditLog> findByActionAndEntityRefOrderByCreatedAtDesc(String action, String entityRef, Pageable pageable);

    /** Espelha `findFirst({ where: { action }, orderBy: { createdAt: 'desc' } })` — backupJob.ultimaCopiaComSucesso. */
    java.util.Optional<AuditLog> findFirstByActionOrderByCreatedAtDesc(String action);

    /** Espelha o `groupBy({ by: ['actorId'], _max: { createdAt: true } })` de mfaLembreteService.ultimoPorAtor — uma consulta só para todos os atores. */
    @Query("SELECT a.actorId, MAX(a.createdAt) FROM AuditLog a WHERE a.actorId IN :actorIds AND a.action IN :actions GROUP BY a.actorId")
    List<Object[]> ultimoPorAtor(@Param("actorIds") List<String> actorIds, @Param("actions") List<String> actions);

    /** Linha do tempo auditável de uma entidade — poService.getPurchaseOrderHistory. */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtAsc(String entityType, String entityId);

    /** dadosPessoaisService.exportar — o rasto do titular (take 1000). */
    List<AuditLog> findByActorIdOrderByCreatedAtDesc(String actorId, Pageable pageable);

    /** dadosPessoaisService.anonimizar — o trilho sobrevive, sem o nome da pessoa. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE AuditLog a SET a.actorName = :nome WHERE a.actorId = :actorId")
    int anonimizarAtor(@Param("actorId") String actorId, @Param("nome") String nome);
}
