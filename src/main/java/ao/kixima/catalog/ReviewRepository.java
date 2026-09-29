package ao.kixima.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, String> {

    Optional<Review> findByProductIdAndUserId(String productId, String userId);

    List<Review> findByProductIdOrderByCreatedAtDesc(String productId);

    long countByProductId(String productId);

    @org.springframework.data.jpa.repository.Query("SELECT AVG(r.rating) FROM Review r WHERE r.productId = :productId")
    Double avgRatingByProductId(String productId);
}
