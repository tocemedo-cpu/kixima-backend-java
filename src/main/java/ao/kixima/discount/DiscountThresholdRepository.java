package ao.kixima.discount;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiscountThresholdRepository extends JpaRepository<DiscountThreshold, String> {

    List<DiscountThreshold> findAllByOrderByMinVolumeUsdAsc();

    List<DiscountThreshold> findByAtivoTrueOrderByMinVolumeUsdAsc();
}
