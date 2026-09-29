package ao.kixima.porobo;

import ao.kixima.audit.Actor;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.po.PoStatus;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderItem;
import ao.kixima.po.PurchaseOrderItemRepository;
import ao.kixima.po.PurchaseOrderRepository;
import ao.kixima.porobo.dto.PoRoboRegraDto;
import ao.kixima.porobo.dto.PoRoboRegraRequest;
import com.fasterxml.jackson.databind.node.IntNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste de paridade de contrato para poRoboService.js (tests/po-robot-job.test.js):
 * a quantidade escala pela periodicidade, o add-on é exigido, a PO nasce
 * sempre por aprovar com createdBySource=ROBOT, o limite de segurança trava,
 * o ciclo ignora regras inativas/futuras, uma falha não aborta as outras, e
 * correr duas vezes não duplica.
 *
 * NÃO TESTADO aqui: duas corridas CONCORRENTES na mesma regra (reserva
 * atómica) — pede duas transações reais em paralelo, incompatível com o
 * teste @Transactional que reverte tudo no fim; a reserva é o mesmo UPDATE
 * condicional do Node, verificado só por leitura de código.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PoRoboServiceTest {

    private static final Actor ADMIN = new Actor(null, "Admin Petro", "COMPANY_ADMIN", null, "127.0.0.1");

    @Autowired
    private PoRoboService poRoboService;

    @Autowired
    private PoRoboRegraRepository regraRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private String petroangolaId;
    private String kiandaId;
    private String valvulaId;

    @BeforeEach
    void fixtures() {
        petroangolaId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-CLI-0001");
        kiandaId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, "AO-FOR-0001");
        valvulaId = jdbcTemplate.queryForObject("SELECT id FROM products WHERE name LIKE 'Válvula de esfera%' AND active", String.class);
    }

    private void ativarAddon(String companyId) {
        jdbcTemplate.update("INSERT INTO company_addons (id, company_id, addon_key, status, activated_at, valido_ate, created_at, updated_at) "
                        + "VALUES (?, ?, 'PO_ROBOT', 'ATIVO', now(), ?, now(), now())",
                UUID.randomUUID().toString(), companyId, java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(30))));
    }

    private PoRoboRegraDto regra(Integer quantidade, BigDecimal limite) {
        return poRoboService.criar(petroangolaId, new PoRoboRegraRequest(valvulaId, "MANUAL", new BigDecimal("30"), "SEMANAL",
                quantidade == null ? null : IntNode.valueOf(quantidade),
                limite == null ? null : com.fasterxml.jackson.databind.node.DecimalNode.valueOf(limite), null), ADMIN);
    }

    private PoRoboRegra recarregar(String id) {
        entityManager.flush();
        entityManager.clear();
        return regraRepository.findById(id).orElseThrow();
    }

    @Test
    void semAddonAtivoNaoSeConfiguraNemSeExecuta() {
        assertThatThrownBy(() -> regra(2, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("add-on pago");
    }

    @Test
    void quantidadeFixaSobrepoeECalculoEscalaPelaPeriodicidade() {
        ativarAddon(petroangolaId);
        PoRoboRegra fixa = recarregar(regra(4, null).id());
        assertThat(poRoboService.resolverQuantidade(fixa)).isEqualTo(4);

        PoRoboRegra semanal = recarregar(regra(null, null).id());
        assertThat(poRoboService.resolverQuantidade(semanal)).isEqualTo(7); // round(30 × 7/30)

        // Origem IA sem histórico de compra: média 0 → nunca devolve zero (mínimo 1 unidade).
        PoRoboRegraDto ia = poRoboService.criar(petroangolaId, new PoRoboRegraRequest(valvulaId, "IA", new BigDecimal("30"),
                "MENSAL", null, null, null), ADMIN);
        assertThat(poRoboService.resolverQuantidade(recarregar(ia.id()))).isEqualTo(1);
    }

    @Test
    void criaAPoSemprePorAprovarComOrigemRobotEAvancaAProximaExecucao() {
        ativarAddon(petroangolaId);
        PoRoboRegra regra = recarregar(regra(2, null).id());
        Instant antes = regra.getProximaExecucaoEm();

        PurchaseOrder criada = poRoboService.executarRegra(regra);

        entityManager.flush();
        entityManager.clear();
        PurchaseOrder po = purchaseOrderRepository.findById(criada.getId()).orElseThrow();
        assertThat(po.getStatus()).isEqualTo(PoStatus.AGUARDANDO_APROVACAO);
        assertThat(po.getBuyerCompanyId()).isEqualTo(petroangolaId);
        assertThat(po.getSupplierCompanyId()).isEqualTo(kiandaId);
        assertThat(po.getCreatedBySource()).isEqualTo("ROBOT");

        List<PurchaseOrderItem> itens = purchaseOrderItemRepository.findByPurchaseOrderIdOrderByIdAsc(po.getId());
        assertThat(itens).hasSize(1);
        assertThat(itens.get(0).getProductId()).isEqualTo(valvulaId);
        assertThat(itens.get(0).getQuantity()).isEqualTo(2);

        Integer auditoria = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'PO_CRIADA_ROBOT' AND entity_id = ? AND actor_name = 'PO Robot' AND detail->>'regraId' = ?",
                Integer.class, po.getId(), regra.getId());
        assertThat(auditoria).isEqualTo(1);

        assertThat(regraRepository.findById(regra.getId()).orElseThrow().getProximaExecucaoEm()).isAfter(antes).isAfter(Instant.now());
    }

    @Test
    void respeitaOLimiteDeSegurancaEProdutoInativoFalha() {
        ativarAddon(petroangolaId);
        // 2 × 850.000 AOA / 900 ≈ 1.888,89 USD > 1 USD.
        PoRoboRegra acimaDoLimite = recarregar(regra(2, new BigDecimal("1")).id());
        assertThatThrownBy(() -> poRoboService.executarRegra(acimaDoLimite)).hasMessageContaining("limite de segurança");

        PoRoboRegra ok = recarregar(regra(2, null).id());
        jdbcTemplate.update("UPDATE products SET active = false WHERE id = ?", valvulaId);
        entityManager.clear();
        assertThatThrownBy(() -> poRoboService.executarRegra(ok)).hasMessageContaining("não existe ou está inativo");
    }

    @Test
    void oCicloCorreSoAsDevidasNaoAbortaComUmaFalhaENaoDuplica() {
        ativarAddon(petroangolaId);
        String devida = regra(2, null).id();
        String falha = regra(2, new BigDecimal("1")).id();
        String inativa = regra(2, null).id();
        String futura = regra(2, null).id();
        entityManager.flush();
        jdbcTemplate.update("UPDATE po_robo_regras SET ativo = false WHERE id = ?", inativa);
        jdbcTemplate.update("UPDATE po_robo_regras SET proxima_execucao_em = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(3))), futura);
        entityManager.flush();
        entityManager.clear();

        PoRoboService.CicloResultado r = poRoboService.executarCiclo();
        assertThat(r.total()).isEqualTo(2);
        assertThat(r.criadas()).isEqualTo(1);
        assertThat(r.falhas()).extracting(PoRoboService.Falha::regraId).containsExactly(falha);
        assertThat(r.falhas().get(0).erro()).contains("limite de segurança");

        assertThat(posDaRegra(devida)).isEqualTo(1);
        assertThat(posDaRegra(falha)).isZero();
        assertThat(posDaRegra(inativa)).isZero();
        assertThat(posDaRegra(futura)).isZero();

        // A que falhou volta a ficar devida (data original reposta); a criada avançou.
        assertThat(regraRepository.findById(falha).orElseThrow().getProximaExecucaoEm()).isBefore(Instant.now());
        assertThat(regraRepository.findById(devida).orElseThrow().getProximaExecucaoEm()).isAfter(Instant.now());

        // Segunda corrida seguida: só a que falhou volta a ser tentada; a criada não se duplica.
        PoRoboService.CicloResultado r2 = poRoboService.executarCiclo();
        assertThat(r2.total()).isEqualTo(1);
        assertThat(r2.criadas()).isZero();
        assertThat(posDaRegra(devida)).isEqualTo(1);
    }

    private Integer posDaRegra(String regraId) {
        entityManager.flush();
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'PO_CRIADA_ROBOT' AND detail->>'regraId' = ?", Integer.class, regraId);
    }
}
