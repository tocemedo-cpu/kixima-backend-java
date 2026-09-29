package ao.kixima.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImageRepository extends JpaRepository<ProductImage, String> {
    boolean existsByUrl(String url);

    java.util.List<ProductImage> findByProductIdOrderByPrimaryDescSortOrderAsc(String productId);
}
