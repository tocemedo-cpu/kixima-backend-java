package ao.kixima.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, String> {

    /** Espelha `where: { OR: [{ userId }, { companyId }] }` em notificationController.list(). */
    Page<Notification> findByUserIdOrCompanyId(String userId, String companyId, Pageable pageable);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.readAt IS NULL AND (n.userId = :userId OR n.companyId = :companyId)")
    long contarPorLer(@Param("userId") String userId, @Param("companyId") String companyId);

    /** Espelha `notification.deleteMany({ where: { readAt: { not: null, lt: ateNotificacoes } } })` — retencaoService.limpar. */
    @Modifying
    @Query("DELETE FROM Notification n WHERE n.readAt IS NOT NULL AND n.readAt < :ate")
    int deleteByReadAtNotNullAndBefore(@Param("ate") Instant ate);

    /** dadosPessoaisService.exportar — notificações recebidas pelo titular (take 1000). */
    List<Notification> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /** dadosPessoaisService.anonimizar — a correspondência pessoal apaga-se. */
    @Modifying
    @Query("DELETE FROM Notification n WHERE n.userId = :userId")
    int deleteByUserId(@Param("userId") String userId);
}
