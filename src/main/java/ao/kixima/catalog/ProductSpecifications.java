package ao.kixima.catalog;

import org.springframework.data.jpa.domain.Specification;

/**
 * Espelha, predicado a predicado, o `where` de catalogService.listCatalog()
 * — cada filtro só entra na query quando fornecido, tal como o spread
 * condicional `...(x ? {x} : {})` do Prisma.
 */
final class ProductSpecifications {

    private ProductSpecifications() {
    }

    static Specification<Product> comFiltros(String category, ProductKind kind, String supplierId,
                                               String excludeSupplierId, String search) {
        return (root, query, cb) -> {
            var conditions = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            conditions.add(cb.isTrue(root.get("active")));
            if (category != null) conditions.add(cb.equal(root.get("category"), category));
            if (kind != null) conditions.add(cb.equal(root.get("kind"), kind));
            if (supplierId != null) conditions.add(cb.equal(root.get("supplierId"), supplierId));
            if (excludeSupplierId != null) conditions.add(cb.notEqual(root.get("supplierId"), excludeSupplierId));
            if (search != null && !search.isBlank()) {
                conditions.add(cb.like(cb.lower(root.get("name")), "%" + search.toLowerCase() + "%"));
            }
            return cb.and(conditions.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
