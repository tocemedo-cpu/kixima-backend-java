package ao.kixima.po;

import ao.kixima.security.PersonaRole;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/** Espelha o `where` de poService.listPurchaseOrders(). */
final class PurchaseOrderSpecifications {

    private PurchaseOrderSpecifications() {
    }

    static Specification<PurchaseOrder> paraListagem(String companyId, PersonaRole role, PoStatus status) {
        return (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (status != null) conditions.add(cb.equal(root.get("status"), status));
            // Nota: o Node também verifica role === 'FINANCEIRO_FORNECEDOR', um valor
            // que não existe no enum PersonaRole real (5 valores, confirmado em
            // schema.prisma) — ramo inalcançável, omitido aqui de propósito.
            if (role == PersonaRole.FORNECEDOR) {
                conditions.add(cb.equal(root.get("supplierCompanyId"), companyId));
            } else if (companyId != null) {
                conditions.add(cb.or(
                        cb.equal(root.get("buyerCompanyId"), companyId),
                        cb.equal(root.get("supplierCompanyId"), companyId)));
            }
            return cb.and(conditions.toArray(new Predicate[0]));
        };
    }
}
