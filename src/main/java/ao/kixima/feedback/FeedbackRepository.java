package ao.kixima.feedback;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, String> {

    @Query("SELECT f FROM Feedback f JOIN FETCH f.user JOIN FETCH f.company WHERE f.userId = :userId ORDER BY f.createdAt DESC")
    List<Feedback> findByUserIdOrderByCreatedAtDesc(@Param("userId") String userId);

    @Query("SELECT f FROM Feedback f JOIN FETCH f.user JOIN FETCH f.company WHERE f.approved = true ORDER BY f.createdAt DESC")
    List<Feedback> findTopApprovedOrderByCreatedAtDesc(Pageable pageable);

    long countByApproved(boolean approved);

    @Query("SELECT AVG(f.rating) FROM Feedback f WHERE f.approved = true")
    Double mediaAprovadas();

    /** Duas queries derivadas em vez de `(:approved IS NULL OR ...)` — ver ProductSpecifications, M2. */
    Page<Feedback> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Feedback> findByApprovedOrderByCreatedAtDesc(boolean approved, Pageable pageable);
}
