package ao.kixima.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, String> {

    Optional<Conversation> findFirstByBuyerCompanyIdAndSupplierCompanyIdAndContextTypeAndContextIdAndStatus(
            String buyerCompanyId, String supplierCompanyId, String contextType, String contextId, ConversationStatus status);

    List<Conversation> findByBuyerCompanyIdOrSupplierCompanyIdOrderByUpdatedAtDesc(String buyerCompanyId, String supplierCompanyId);
}
