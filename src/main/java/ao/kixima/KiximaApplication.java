package ao.kixima;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ponto de entrada. Equivalente a src/server.js no backend Node.
 *
 * Migração incremental lado a lado: este processo liga-se à MESMA base de
 * dados Supabase que o backend Node — nenhuma migração de dados, nenhum
 * schema próprio. Ver /root/.claude/plans/soft-nibbling-axolotl.md (plano
 * aprovado) para a ordem de migração por domínio.
 */
@SpringBootApplication
@EnableScheduling
public class KiximaApplication {
    public static void main(String[] args) {
        SpringApplication.run(KiximaApplication.class, args);
    }
}
