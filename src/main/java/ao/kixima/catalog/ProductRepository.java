package ao.kixima.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * {@link JpaSpecificationExecutor} em vez de um JPQL com `(:param IS NULL OR
 * ...)` para cada filtro opcional — esse padrão, quando um parâmetro só
 * aparece do lado do `IS NULL` (filtro ausente), deixa o Postgres sem
 * contexto de tipo para o inferir ("could not determine data type of
 * parameter"), sobretudo com o enum nativo `kind`. Com Specifications
 * (ver {@link ProductSpecifications}), um filtro ausente simplesmente não
 * entra na query — mesmo efeito do spread condicional `...(x ? {x} : {})`
 * do Prisma em catalogService.js, sem o problema de tipo.
 */
public interface ProductRepository extends JpaRepository<Product, String>, JpaSpecificationExecutor<Product> {

    Optional<Product> findBySlug(String slug);

    /** catalogImportService — quais dos slugs do ficheiro já existem (reimportar atualiza, não duplica). */
    List<Product> findBySlugIn(java.util.Collection<String> slugs);

    /** Usado por UploadAccessService — imagem de capa de produto é sempre pública. */
    boolean existsByImageUrl(String imageUrl);

    /** Espelha incrementView() — best-effort, um único UPDATE em vez de ler+gravar a entidade inteira. */
    @Modifying
    @Query("UPDATE Product p SET p.viewCount = p.viewCount + 1 WHERE p.id = :id")
    void incrementViewCount(@Param("id") String id);

    /**
     * Espelha o `updateMany({ where: { id, stockQuantity: { gte: quantity } },
     * data: { stockQuantity: { decrement: quantity } } })` de
     * poService.createPurchaseOrder — o `stockQuantity >= :quantity` na
     * cláusula WHERE fecha a janela de concorrência: duas POs a pedir mais
     * do que o stock permite não podem as duas ter sucesso. Devolve o
     * número de linhas afectadas (0 = ou não existe, ou não havia stock).
     */
    @Modifying
    @Query("UPDATE Product p SET p.stockQuantity = p.stockQuantity - :quantity WHERE p.id = :id AND p.stockQuantity >= :quantity")
    int decrementStockIfAvailable(@Param("id") String id, @Param("quantity") int quantity);

    // --- API externa de catálogo (apiCatalogoRoutes.js) ---
    long countBySupplierId(String supplierId);

    long countBySupplierIdAndActive(String supplierId, boolean active);

    List<Product> findBySupplierIdOrderByUpdatedAtDescIdAsc(String supplierId, org.springframework.data.domain.Pageable pageable);

    List<Product> findBySupplierIdAndActiveOrderByUpdatedAtDescIdAsc(String supplierId, boolean active, org.springframework.data.domain.Pageable pageable);

    Optional<Product> findFirstBySupplierIdAndSku(String supplierId, String sku);

    /** buyerService.suppliers / reportsService.supplierStats — o catálogo de um fornecedor. */
    List<Product> findBySupplierId(String supplierId);
}
