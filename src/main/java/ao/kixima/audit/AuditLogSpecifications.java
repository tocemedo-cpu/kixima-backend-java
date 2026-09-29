package ao.kixima.audit;

import org.springframework.data.jpa.domain.Specification;

/**
 * Espelha os filtros opcionais de `auditService.list()` (action/q) —
 * `Specification`, não o idioma JPQL {@code (:param IS NULL OR ...)}: essa
 * forma dá um erro real do Postgres ("could not determine data type of
 * parameter") quando o parâmetro só aparece do lado IS NULL (achado do M2,
 * ver ProductSpecifications). Um filtro ausente aqui nunca vira parâmetro
 * de bind nenhum.
 */
final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    static Specification<AuditLog> comFiltros(String action, String q) {
        return (root, query, cb) -> {
            var predicados = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (action != null && !action.isBlank()) {
                predicados.add(cb.equal(root.get("action"), action));
            }
            if (q != null && !q.isBlank()) {
                String padrao = "%" + q.toLowerCase() + "%";
                predicados.add(cb.or(
                        cb.like(cb.lower(root.get("entityRef")), padrao),
                        cb.like(cb.lower(root.get("actorName")), padrao)));
            }
            return predicados.isEmpty() ? cb.conjunction() : cb.and(predicados.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
