package ao.kixima.erp;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ErpSyncLogRepository extends JpaRepository<ErpSyncLog, String> {

    List<ErpSyncLog> findByPurchaseOrderIdAndEventTypeOrderByCreatedAtAsc(String purchaseOrderId, String eventType);
}
