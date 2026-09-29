package ao.kixima.support;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, String> {

    List<SupportMessage> findByTicketIdOrderByCreatedAtAsc(String ticketId);

    /** Usado por UploadAccessService para resolver a posse de um anexo do Chat de Suporte. */
    Optional<SupportMessage> findFirstByAttachmentUrl(String attachmentUrl);

    @Modifying
    @Query("UPDATE SupportMessage m SET m.readAt = :agora WHERE m.ticketId = :ticketId AND m.authorId <> :userId AND m.readAt IS NULL")
    void marcarLidas(@Param("ticketId") String ticketId, @Param("userId") String userId, @Param("agora") Instant agora);

    /** contarNaoLidas() — cliente: não lidas nos SEUS pedidos. */
    @Query("SELECT COUNT(m) FROM SupportMessage m, SupportTicket t "
            + "WHERE m.ticketId = t.id AND m.authorId <> :userId AND m.readAt IS NULL AND t.userId = :userId")
    long contarNaoLidasDoCliente(@Param("userId") String userId);

    /** contarNaoLidas() — assessor de Suporte: não lidas nos pedidos que ATENDE. */
    @Query("SELECT COUNT(m) FROM SupportMessage m, SupportTicket t "
            + "WHERE m.ticketId = t.id AND m.authorId <> :userId AND m.readAt IS NULL AND t.assignedToId = :userId")
    long contarNaoLidasDoAssessor(@Param("userId") String userId);
}
