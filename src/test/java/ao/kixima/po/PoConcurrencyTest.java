package ao.kixima.po;

import ao.kixima.common.error.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Achado da auditoria de segurança: 8 transições de estado de PurchaseOrder
 * liam a linha sem bloqueio (getPurchaseOrder → findById simples), ao
 * contrário dos callbacks do ERP (aplicarDecisaoErp/aplicarPagamentoErp), que
 * já usavam findByIdParaAtualizar (PESSIMISTIC_WRITE). Duas chamadas
 * concorrentes à MESMA transição liam o mesmo estado antigo antes de
 * qualquer commit e podiam ambas "passar".
 *
 * Este teste tem de correr FORA de uma transação de teste (sem
 * {@code @Transactional} na classe): cada chamada a poService.approvePurchaseOrder
 * é ela própria {@code @Transactional}, e só ganha uma transação/ligação
 * genuinamente separada quando chamada a partir de uma thread sem transação
 * ambiente — é isso que faz o bloqueio pessimista (SELECT ... FOR UPDATE)
 * fazer sentido: a segunda chamada bloqueia mesmo, contra a base de dados
 * real, até a primeira committar.
 */
@SpringBootTest
@ActiveProfiles("test")
class PoConcurrencyTest {

    @Autowired
    private PoService poService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String poId;

    private String criarPoAguardandoAprovacao() {
        String compradorId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-CLI-0001");
        String fornecedorId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-FOR-0001");
        String userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", String.class, "comprador@petroangola.co.ao");
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO purchase_orders (id, reference, buyer_company_id, supplier_company_id, created_by_id, total_amount, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 1, 'AGUARDANDO_APROVACAO'::\"PoStatus\", now(), now())",
                id, "PO-CONCORRENCIA-" + id.substring(0, 8), compradorId, fornecedorId, userId);
        return id;
    }

    @AfterEach
    void limpar() {
        if (poId != null) jdbcTemplate.update("DELETE FROM purchase_orders WHERE id = ?", poId);
    }

    @Test
    void duasAprovacoesConcorrentesDaMesmaPoNuncaAprovamAsDuas() throws Exception {
        poId = criarPoAguardandoAprovacao();
        String compradorId = jdbcTemplate.queryForObject("SELECT buyer_company_id FROM purchase_orders WHERE id = ?", String.class, poId);
        String userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", String.class, "admin@petroangola.co.ao");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch partida = new CountDownLatch(1);
        try {
            Future<Boolean> f1 = pool.submit(() -> tentaAprovar(partida, userId, compradorId));
            Future<Boolean> f2 = pool.submit(() -> tentaAprovar(partida, userId, compradorId));
            partida.countDown();
            boolean r1 = f1.get(15, TimeUnit.SECONDS);
            boolean r2 = f2.get(15, TimeUnit.SECONDS);

            assertThat(r1 ^ r2).as("exactamente uma das duas aprovações concorrentes deve ter sucesso, nunca as duas nem nenhuma").isTrue();
        } finally {
            pool.shutdown();
        }

        String estadoFinal = jdbcTemplate.queryForObject("SELECT status::text FROM purchase_orders WHERE id = ?", String.class, poId);
        assertThat(estadoFinal).isEqualTo("APROVADA");
    }

    private boolean tentaAprovar(CountDownLatch partida, String userId, String compradorId) {
        try {
            partida.await();
            poService.approvePurchaseOrder(poId, userId, compradorId);
            return true;
        } catch (ConflictException e) {
            // A segunda a chegar: já viu o estado APROVADA (posto pela primeira, já committado) — correcto.
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
