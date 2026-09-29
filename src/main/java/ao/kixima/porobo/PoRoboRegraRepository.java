package ao.kixima.porobo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PoRoboRegraRepository extends JpaRepository<PoRoboRegra, String> {

    @Query("SELECT r FROM PoRoboRegra r JOIN FETCH r.product WHERE r.companyId = :companyId ORDER BY r.createdAt DESC")
    List<PoRoboRegra> findByCompanyIdComProduto(@Param("companyId") String companyId);

    /** Regras ativas cuja vez chegou — o `findMany` de poRoboService.executarCiclo. */
    List<PoRoboRegra> findByAtivoTrueAndProximaExecucaoEmLessThanEqual(Instant agora);

    /**
     * Reserva atómica (poRoboService.reclamarRegra): só afeta a linha se
     * {@code proximaExecucaoEm} ainda for EXATAMENTE o valor lido — um UPDATE
     * condicional é atómico ao nível da linha em Postgres, por isso a segunda
     * corrida concorrente afeta zero linhas. {@code flushAutomatically} para
     * não correr por cima de estado pendente; {@code clearAutomatically} para
     * a entidade em memória não esconder o que a base já tem.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PoRoboRegra r SET r.proximaExecucaoEm = :nova WHERE r.id = :id AND r.proximaExecucaoEm = :esperada")
    int reclamar(@Param("id") String id, @Param("esperada") Instant esperada, @Param("nova") Instant nova);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PoRoboRegra r SET r.proximaExecucaoEm = :proxima WHERE r.id = :id")
    int definirProximaExecucao(@Param("id") String id, @Param("proxima") Instant proxima);
}
