package ao.kixima.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductDocumentRepository extends JpaRepository<ProductDocument, String> {
    boolean existsByFileUrl(String fileUrl);

    List<ProductDocument> findByProductIdOrderByTypeAsc(String productId);

    long countByFileUrlStartingWith(String prefixo);

    /** Espelha catalogService.listSupplierDocuments — documentos técnicos de todos os produtos do fornecedor. */
    @Query("SELECT d FROM ProductDocument d JOIN FETCH d.product p WHERE p.supplierId = :supplierId ORDER BY d.createdAt DESC")
    List<ProductDocument> findBySupplierIdOrderByCreatedAtDesc(@Param("supplierId") String supplierId);
}
