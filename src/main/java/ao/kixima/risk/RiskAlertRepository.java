package ao.kixima.risk;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RiskAlertRepository extends JpaRepository<RiskAlert, String> {

    List<RiskAlert> findByStatusInOrderByCreatedAtDesc(List<RiskAlertStatus> statuses, Pageable pageable);

    List<RiskAlert> findByStatusOrderByCreatedAtDesc(RiskAlertStatus status, Pageable pageable);

    boolean existsByConversationId(String conversationId);

    List<RiskAlert> findByConversationIdOrderByCreatedAtDesc(String conversationId);

    Optional<RiskAlert> findFirstByConversationIdAndStatusInAndLevelInOrderByCreatedAtDesc(
            String conversationId, List<RiskAlertStatus> statuses, List<RiskLevel> levels);
}
