package ao.kixima.kit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KitRepository extends JpaRepository<Kit, String> {

    List<Kit> findBySupplierIdAndActiveTrueOrderByCreatedAtDesc(String supplierId);
}
