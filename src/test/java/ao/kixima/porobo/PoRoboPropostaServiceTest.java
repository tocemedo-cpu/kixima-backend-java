package ao.kixima.porobo;

import ao.kixima.audit.Actor;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.po.PoStatus;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderItem;
import ao.kixima.po.PurchaseOrderItemRepository;
import ao.kixima.po.PurchaseOrderRepository;
import ao.kixima.porobo.dto.PoRoboPropostaDto;
import ao.kixima.porobo.dto.PoRoboRegraRequest;
import ao.kixima.testsupport.Contas;
import ao.kixima.testsupport.Contas.Persona;
import ao.kixima.testsupport.Dados;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decisões humanas sobre as propostas: ajustar, submeter (cria a PO em AGUARDANDO_APROVACAO, em nome de quem submete) e
 * rejeitar. O robot nunca decide; uma proposta só passa a compra por intervenção humana; nada atravessa empresas.
 *
 * NÃO TESTADO aqui: duas submissões CONCORRENTES da mesma proposta (a reserva atómica do estado) — pede duas transações
 * reais em paralelo, incompatível com o teste @Transactional; o segundo pedido sequencial dá 409 e uma só PO.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PoRoboPropostaServiceTest {

    @Autowired
    private PoRoboService poRoboService;

    @Autowired
    private PoRoboPropostaService propostaService;

    @Autowired
    private PoRoboPropostaRepository propostaRepository;

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
    private Actor comprador;
    private Actor admin;

    @BeforeEach
    void fixtures() {
        petroangolaId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, Dados.nifCliente());
        kiandaId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, Dados.nifFornecedora());
        valvulaId = jdbcTemplate.queryForObject("SELECT id FROM products WHERE name LIKE 'Válvula de esfera%' AND active AND supplier_id = ?",
                String.class, Dados.idFornecedora());
        comprador = utilizador(Persona.COMPRADOR);
        admin = utilizador(Persona.COMPANY_ADMIN);
        jdbcTemplate.update("INSERT INTO company_addons (id, company_id, addon_key, status, activated_at, valido_ate, created_at, updated_at) "
                        + "VALUES (?, ?, 'PO_ROBOT', 'ATIVO', now(), ?, now(), now())",
                UUID.randomUUID().toString(), petroangolaId, java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(30))));
    }

    private Actor utilizador(Persona persona) {
        Map<String, Object> u = jdbcTemplate.queryForMap("SELECT id, name, company_id FROM users WHERE email = ?", Contas.email(persona));
        return new Actor((String) u.get("id"), (String) u.get("name"), persona.name(), (String) u.get("company_id"), "127.0.0.1");
    }

    /** Cria uma regra para a válvula e deixa o robot preparar a proposta (PENDENTE_REVISAO). */
    private PoRoboProposta proposta(Integer quantidade, BigDecimal limite) {
        String regraId = poRoboService.criar(petroangolaId, new PoRoboRegraRequest(valvulaId, "MANUAL", new BigDecimal("30"), "MENSAL",
                quantidade == null ? null : IntNode.valueOf(quantidade), limite == null ? null : DecimalNode.valueOf(limite), null), admin).id();
        entityManager.flush();
        entityManager.clear();
        PoRoboProposta p = poRoboService.executarRegra(regraRepository.findById(regraId).orElseThrow(), null).proposta();
        entityManager.flush();
        entityManager.clear();
        return p;
    }

    private int contagem(String sql, Object... args) {
        entityManager.flush();
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private int pos() {
        return contagem("SELECT count(*) FROM purchase_orders WHERE buyer_company_id = ?", petroangolaId);
    }

    // --- submeter -----------------------------------------------------------------

    @Test
    void submeterCriaAPoEmAguardandoAprovacaoEmNomeDeQuemSubmete() {
        PoRoboProposta p = proposta(3, null);
        int posAntes = pos();

        PoRoboPropostaDto dto = propostaService.submeter(petroangolaId, p.getId(), comprador);

        assertThat(dto.estado()).isEqualTo(EstadoProposta.SUBMETIDA);
        assertThat(dto.purchaseOrderId()).isNotBlank();
        assertThat(dto.purchaseOrderReference()).isNotBlank();
        assertThat(dto.revistaPor().id()).isEqualTo(comprador.actorId());
        assertThat(pos()).isEqualTo(posAntes + 1);

        entityManager.flush();
        entityManager.clear();
        PurchaseOrder po = purchaseOrderRepository.findById(dto.purchaseOrderId()).orElseThrow();
        assertThat(po.getStatus()).as("a PO nasce por aprovar: o robot nunca aprova").isEqualTo(PoStatus.AGUARDANDO_APROVACAO);
        assertThat(po.getBuyerCompanyId()).isEqualTo(petroangolaId);
        assertThat(po.getSupplierCompanyId()).isEqualTo(kiandaId);
        assertThat(po.getCreatedById()).as("em nome da pessoa que submeteu, não do robot").isEqualTo(comprador.actorId());
        assertThat(po.getCreatedBySource()).isEqualTo("ROBOT");
        assertThat(po.getReference()).isEqualTo(dto.purchaseOrderReference());
        List<PurchaseOrderItem> itens = purchaseOrderItemRepository.findByPurchaseOrderIdOrderByIdAsc(po.getId());
        assertThat(itens).hasSize(1);
        assertThat(itens.get(0).getProductId()).isEqualTo(valvulaId);
        assertThat(itens.get(0).getQuantity()).isEqualTo(3);

        assertThat(contagem("SELECT count(*) FROM audit_logs WHERE action = 'PO_ROBO_PROPOSTA_SUBMETIDA' AND entity_id = ? AND actor_id = ? AND company_id = ?",
                p.getId(), comprador.actorId(), petroangolaId)).isEqualTo(1);
    }

    @Test
    void submeterDuasVezesNaoCriaDuasPos() {
        PoRoboProposta p = proposta(2, null);
        propostaService.submeter(petroangolaId, p.getId(), comprador);
        int posDepois = pos();

        assertThatThrownBy(() -> propostaService.submeter(petroangolaId, p.getId(), admin))
                .isInstanceOf(ConflictException.class).hasMessageContaining("SUBMETIDA");
        assertThat(pos()).isEqualTo(posDepois);
    }

    @Test
    void submeterSemAddonAtivoNaoCriaPo() {
        PoRoboProposta p = proposta(2, null);
        jdbcTemplate.update("UPDATE company_addons SET status = 'INATIVO' WHERE company_id = ? AND addon_key = 'PO_ROBOT'", petroangolaId);
        entityManager.clear();
        int posAntes = pos();

        assertThatThrownBy(() -> propostaService.submeter(petroangolaId, p.getId(), comprador))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("add-on");
        assertThat(pos()).isEqualTo(posAntes);
    }

    @Test
    void submeterComProdutoIndisponivelNaoCriaPoEAPropostaContinuaPendente() {
        PoRoboProposta p = proposta(2, null);
        jdbcTemplate.update("UPDATE products SET active = false WHERE id = ?", valvulaId);
        entityManager.clear();
        int posAntes = pos();

        assertThatThrownBy(() -> propostaService.submeter(petroangolaId, p.getId(), comprador))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("já não está disponível");
        assertThat(pos()).isEqualTo(posAntes);
        assertThat(propostaRepository.findById(p.getId()).orElseThrow().getEstado()).isEqualTo(EstadoProposta.PENDENTE_REVISAO);
    }

    @Test
    void submeterUmaPropostaRejeitadaOuExpiradaDa409() {
        PoRoboProposta rejeitada = proposta(2, null);
        propostaService.rejeitar(petroangolaId, rejeitada.getId(), "Não precisamos", comprador);
        assertThatThrownBy(() -> propostaService.submeter(petroangolaId, rejeitada.getId(), comprador))
                .isInstanceOf(ConflictException.class).hasMessageContaining("REJEITADA");

        // Duas propostas novas da mesma regra: a primeira expira quando a segunda nasce; a expirada também não se submete.
        PoRoboRegra regra = regraRepository.findById(rejeitada.getRegraId()).orElseThrow();
        String antiga = poRoboService.executarRegra(regra, null).proposta().getId();
        poRoboService.executarRegra(regraRepository.findById(regra.getId()).orElseThrow(), null);
        entityManager.flush();
        entityManager.clear();
        assertThatThrownBy(() -> propostaService.submeter(petroangolaId, antiga, comprador))
                .isInstanceOf(ConflictException.class).hasMessageContaining("EXPIRADA");
    }

    // --- ajustar ------------------------------------------------------------------

    @Test
    void ajustarMudaAQuantidadeEOValorMasGuardaAOriginalDoRobot() {
        PoRoboProposta p = proposta(4, null);

        PoRoboPropostaDto dto = propostaService.ajustar(petroangolaId, p.getId(), IntNode.valueOf(6), comprador);

        assertThat(dto.quantidade()).isEqualTo(6);
        assertThat(dto.quantidadeOriginal()).isEqualTo(4);
        assertThat(dto.valorTotal()).isEqualByComparingTo("5100000"); // 6 × 850.000 AOA
        assertThat(dto.estado()).isEqualTo(EstadoProposta.PENDENTE_REVISAO);
        assertThat(contagem("SELECT count(*) FROM audit_logs WHERE action = 'PO_ROBO_PROPOSTA_AJUSTADA' AND entity_id = ? AND actor_id = ? "
                + "AND (detail->>'de')::int = 4 AND (detail->>'para')::int = 6", p.getId(), comprador.actorId())).isEqualTo(1);
    }

    @Test
    void ajustarSoAceitaInteirosMaioresQueZero() {
        PoRoboProposta p = proposta(4, null);
        for (var invalida : List.of(IntNode.valueOf(0), IntNode.valueOf(-3), DecimalNode.valueOf(new BigDecimal("2.5")), DecimalNode.valueOf(new BigDecimal("3.0")))) {
            assertThatThrownBy(() -> propostaService.ajustar(petroangolaId, p.getId(), invalida, comprador))
                    .as("quantidade %s", invalida).isInstanceOf(ValidationException.class);
        }
        assertThatThrownBy(() -> propostaService.ajustar(petroangolaId, p.getId(), TextNode.valueOf("3"), comprador)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> propostaService.ajustar(petroangolaId, p.getId(), NullNode.getInstance(), comprador)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> propostaService.ajustar(petroangolaId, p.getId(), null, comprador)).isInstanceOf(ValidationException.class);
        assertThat(propostaRepository.findById(p.getId()).orElseThrow().getQuantidade()).isEqualTo(4);
    }

    @Test
    void ajustarNaoPodeUltrapassarOLimiteDeSegurancaDaRegra() {
        PoRoboProposta p = proposta(5, new BigDecimal("2000")); // o robot reduz para 2 (1.888,89 USD)
        assertThat(p.getQuantidade()).isEqualTo(2);

        assertThat(propostaService.ajustar(petroangolaId, p.getId(), IntNode.valueOf(1), comprador).quantidade()).isEqualTo(1);
        assertThatThrownBy(() -> propostaService.ajustar(petroangolaId, p.getId(), IntNode.valueOf(3), comprador))
                .isInstanceOf(ValidationException.class).hasMessageContaining("limite de segurança");
    }

    @Test
    void soSeAjustaUmaPropostaPendente() {
        PoRoboProposta p = proposta(2, null);
        propostaService.submeter(petroangolaId, p.getId(), comprador);
        assertThatThrownBy(() -> propostaService.ajustar(petroangolaId, p.getId(), IntNode.valueOf(3), comprador))
                .isInstanceOf(ConflictException.class);
    }

    // --- rejeitar -----------------------------------------------------------------

    @Test
    void rejeitarGuardaOMotivoEQuemDecidiuENaoCriaPo() {
        PoRoboProposta p = proposta(2, null);
        int posAntes = pos();

        PoRoboPropostaDto dto = propostaService.rejeitar(petroangolaId, p.getId(), "  Já temos stock suficiente  ", admin);

        assertThat(dto.estado()).isEqualTo(EstadoProposta.REJEITADA);
        assertThat(dto.motivoRejeicao()).isEqualTo("Já temos stock suficiente");
        assertThat(dto.revistaPor().id()).isEqualTo(admin.actorId());
        assertThat(dto.purchaseOrderId()).isNull();
        assertThat(pos()).isEqualTo(posAntes);
        assertThat(contagem("SELECT count(*) FROM audit_logs WHERE action = 'PO_ROBO_PROPOSTA_REJEITADA' AND entity_id = ? AND actor_id = ?",
                p.getId(), admin.actorId())).isEqualTo(1);
    }

    @Test
    void rejeitarExigeMotivoEPropostaPendente() {
        PoRoboProposta p = proposta(2, null);
        for (String vazio : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> propostaService.rejeitar(petroangolaId, p.getId(), vazio, comprador))
                    .isInstanceOf(ValidationException.class).hasMessageContaining("motivo");
        }
        assertThatThrownBy(() -> propostaService.rejeitar(petroangolaId, p.getId(), "x".repeat(501), comprador))
                .isInstanceOf(ValidationException.class);
        assertThat(propostaRepository.findById(p.getId()).orElseThrow().getEstado()).isEqualTo(EstadoProposta.PENDENTE_REVISAO);

        propostaService.rejeitar(petroangolaId, p.getId(), "Primeira decisão", comprador);
        assertThatThrownBy(() -> propostaService.rejeitar(petroangolaId, p.getId(), "Segunda decisão", admin))
                .isInstanceOf(ConflictException.class);
    }

    // --- isolamento entre empresas --------------------------------------------------

    @Test
    void outraEmpresaNaoVeNemMexeNasPropostas() {
        PoRoboProposta p = proposta(2, null);
        // A fornecedora é "outra empresa" com o add-on activo: mesmo assim, nada da compradora lhe é visível nem acessível.
        jdbcTemplate.update("INSERT INTO company_addons (id, company_id, addon_key, status, activated_at, valido_ate, created_at, updated_at) "
                        + "VALUES (?, ?, 'PO_ROBOT', 'ATIVO', now(), ?, now(), now())",
                UUID.randomUUID().toString(), kiandaId, java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(30))));
        Actor intruso = new Actor(comprador.actorId(), "Intruso", "COMPRADOR", kiandaId, "127.0.0.1");
        int posAntes = pos();

        assertThat(propostaService.listar(kiandaId)).isEmpty();
        assertThat(propostaService.listar(petroangolaId)).extracting(PoRoboPropostaDto::id).contains(p.getId());
        assertThatThrownBy(() -> propostaService.ajustar(kiandaId, p.getId(), IntNode.valueOf(1), intruso)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> propostaService.submeter(kiandaId, p.getId(), intruso)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> propostaService.rejeitar(kiandaId, p.getId(), "x", intruso)).isInstanceOf(NotFoundException.class);

        assertThat(pos()).isEqualTo(posAntes);
        assertThat(propostaRepository.findById(p.getId()).orElseThrow().getEstado()).isEqualTo(EstadoProposta.PENDENTE_REVISAO);
    }

    @Test
    void aListagemDevolveOContratoCompleto() {
        PoRoboProposta p = proposta(5, new BigDecimal("2000"));

        PoRoboPropostaDto dto = propostaService.listar(petroangolaId).stream().filter(x -> x.id().equals(p.getId())).findFirst().orElseThrow();

        assertThat(dto.regraId()).isEqualTo(p.getRegraId());
        assertThat(dto.previsaoId()).isNotBlank();
        assertThat(dto.execucaoId()).as("proposta criada fora de um ciclo").isNull();
        assertThat(dto.productId()).isEqualTo(valvulaId);
        assertThat(dto.product().name()).contains("Válvula");
        assertThat(dto.supplier().id()).isEqualTo(kiandaId);
        assertThat(dto.quantidade()).isEqualTo(2);
        assertThat(dto.quantidadeOriginal()).isEqualTo(5);
        assertThat(dto.limiteAplicado()).isTrue();
        assertThat(dto.moeda()).isEqualTo("AOA");
        assertThat(dto.precoUnitario()).isEqualByComparingTo("850000");
        assertThat(dto.justificacao()).isNotEmpty();
        assertThat(dto.criadaEm()).isNotNull();
        assertThat(dto.purchaseOrderId()).isNull();
        assertThat(dto.revistaPor()).isNull();
    }
}
