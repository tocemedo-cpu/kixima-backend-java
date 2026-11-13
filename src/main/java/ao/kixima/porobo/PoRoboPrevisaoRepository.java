package ao.kixima.porobo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PoRoboPrevisaoRepository extends JpaRepository<PoRoboPrevisao, String> {

    /** A previsão mais recente de cada regra existente da empresa (as de regras já removidas ficam só no histórico). */
    @Query("""
            SELECT p FROM PoRoboPrevisao p JOIN FETCH p.product pr JOIN FETCH pr.supplier
            WHERE p.companyId = :companyId AND p.regraId IS NOT NULL
              AND p.geradaEm = (SELECT MAX(q.geradaEm) FROM PoRoboPrevisao q WHERE q.regraId = p.regraId)
            ORDER BY p.geradaEm DESC""")
    List<PoRoboPrevisao> ultimasPorRegra(@Param("companyId") String companyId);
}
