package ao.kixima.support;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, String> {

    /** Espelha `OR: [{ companyId }, { userId }]` — feedbackService.opcoes/resolverAlvo(ATENDIMENTO). */
    @Query("SELECT t FROM SupportTicket t WHERE t.companyId = :companyId OR t.userId = :userId ORDER BY t.createdAt DESC")
    List<SupportTicket> findByCompanyIdOrUserIdOrderByCreatedAtDesc(@Param("companyId") String companyId,
                                                                     @Param("userId") String userId, Pageable pageable);

    List<SupportTicket> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /** Fila: pedidos em aberto, sem ninguém a atender ainda. */
    List<SupportTicket> findByStatusAndAssignedToIdIsNullOrderByCreatedAtAsc(SupportStatus status);

    /** "Os meus atendimentos" — do assessor, ainda em curso. */
    List<SupportTicket> findByAssignedToIdAndStatusInOrderByCreatedAtAsc(String assignedToId, List<SupportStatus> statuses);

    /** Admin: listagem geral, sem filtro de estado (duas queries derivadas em vez de um JPQL com `(:status IS NULL OR ...)` — ver ProductSpecifications, M2, para o bug do Postgres com enum nativo nessa forma). */
    Page<SupportTicket> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<SupportTicket> findTop10ByOrderByCreatedAtDesc();

    long countByUserIdAndStatusIn(String userId, List<SupportStatus> statuses);

    /** `groupBy({ by: ['status'], _count })` do painel do Admin. */
    @Query("SELECT t.status, COUNT(t) FROM SupportTicket t GROUP BY t.status")
    List<Object[]> contagemPorStatus();

    Page<SupportTicket> findByStatusOrderByCreatedAtDesc(SupportStatus status, Pageable pageable);
}
