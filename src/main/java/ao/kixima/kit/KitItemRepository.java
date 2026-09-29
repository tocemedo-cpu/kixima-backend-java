package ao.kixima.kit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface KitItemRepository extends JpaRepository<KitItem, String> {

    List<KitItem> findByKitIdIn(Collection<String> kitIds);
}
