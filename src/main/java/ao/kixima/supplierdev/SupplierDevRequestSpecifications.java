package ao.kixima.supplierdev;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Espelha os filtros opcionais de supplierDevService.list() (status/track/q)
 * — {@link Specification}, não o idioma JPQL
 * {@code (:param IS NULL OR ...)} (achado do M2, ver ProductSpecifications):
 * um filtro ausente aqui nunca vira parâmetro de bind nenhum.
 */
final class SupplierDevRequestSpecifications {

    private SupplierDevRequestSpecifications() {
    }

    static Specification<SupplierDevRequest> comFiltros(SupplierDevStatus status, SupplierDevTrack track, String q) {
        return (root, query, cb) -> {
            List<Predicate> predicados = new ArrayList<>();
            if (status != null) predicados.add(cb.equal(root.get("status"), status));
            if (track != null) predicados.add(cb.equal(root.get("track"), track));
            if (q != null && !q.isBlank()) {
                String padrao = "%" + q.toLowerCase() + "%";
                predicados.add(cb.or(
                        cb.like(cb.lower(root.get("companyName")), padrao),
                        cb.like(cb.lower(root.get("reference")), padrao),
                        cb.like(cb.lower(root.get("contactEmail")), padrao)));
            }
            return predicados.isEmpty() ? cb.conjunction() : cb.and(predicados.toArray(new Predicate[0]));
        };
    }
}
