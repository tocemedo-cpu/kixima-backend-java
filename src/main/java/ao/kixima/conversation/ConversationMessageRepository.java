package ao.kixima.conversation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, String> {

    List<ConversationMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    Optional<ConversationMessage> findFirstByConversationIdOrderByCreatedAtDesc(String conversationId);

    @Modifying
    @Query("UPDATE ConversationMessage m SET m.readAt = :agora WHERE m.conversationId = :conversationId AND m.senderCompanyId <> :companyId AND m.readAt IS NULL")
    void marcarLidas(@Param("conversationId") String conversationId, @Param("companyId") String companyId, @Param("agora") Instant agora);

    /** contarNaoLidas() — não lidas em TODAS as conversas da empresa do utilizador (theta join, mesmo padrão de SupportMessageRepository). */
    @Query("SELECT COUNT(m) FROM ConversationMessage m, Conversation c "
            + "WHERE m.conversationId = c.id AND m.readAt IS NULL AND m.senderCompanyId <> :companyId "
            + "AND (c.buyerCompanyId = :companyId OR c.supplierCompanyId = :companyId)")
    long contarNaoLidas(@Param("companyId") String companyId);
}
