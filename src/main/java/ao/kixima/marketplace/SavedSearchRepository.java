package ao.kixima.marketplace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SavedSearchRepository extends JpaRepository<SavedSearch, String> {

    List<SavedSearch> findByUserIdOrderByCreatedAtDesc(String userId);

    void deleteByIdAndUserId(String id, String userId);
}
