package ao.kixima.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockMovementRepository extends JpaRepository<StockMovement, String> {

    Page<StockMovement> findByProduct_SupplierIdOrderByCreatedAtDesc(String supplierId, Pageable pageable);

    Page<StockMovement> findByProduct_SupplierIdAndTypeOrderByCreatedAtDesc(String supplierId, StockMovementType type, Pageable pageable);
}
