package ao.kixima.porobo;

import ao.kixima.testsupport.Dados;

import ao.kixima.audit.Actor;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.ValidationException;
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
 * Regras do PO Robot e ciclo agendado: o robot só PREPARA propostas (nunca POs), a quantidade escala pela periodicidade,
 * o add-on é exigido, o limite de segurança REDUZ a quantidade (ou, se nem uma unidade couber, não há proposta e o motivo
 * fica na execução), uma proposta nova expira a pendente anterior, o ciclo ignora regras inativas/futuras, uma falha não
 * aborta as outras e correr duas vezes não duplica.
 *
 * NÃO TESTADO aqui: duas corridas CONCORRENTES na mesma regra (reserva atómica) — pede duas transações reais em
 * paralelo, incompatível com o teste @Transactional que reverte tudo no fim; a reserva é o mesmo UPDATE condicional do
 * Node, verificado só por leitura de código.
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
    private PoRoboPropostaRepository propostaRepository;

    @Autowired
    private PoRoboPrevisaoRepository previsaoRepository;

    @Autowired
    private PoRoboExecucaoRepository execucaoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private String petroangolaId;
    private String kiandaId;
    /** Válvula: 850.000 AOA ≈ 944,44 USD por unidade (câmbio 900). */
    private String valvulaId;
    /** Mangueira: 120.000 AOA ≈ 133,33 USD por unidade. */
    private String mangueiraId;
    private String tubosId;

    @BeforeEach
    void fixtures() {
        petroangolaId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, Dados.nifCliente());
        kiandaId = jdbcTemplate.queryForObject("SELECT id FROM companies WHERE tax_id = ?", String.class, Dados.nifFornecedora());
        valvulaId = produto("Válvula de esfera%");
        mangueiraId = produto("Mangueira hidráulica%");
        tubosId = produto("Tubos de Aço%");
    }

    private String produto(String padraoDoNome) {
        return jdbcTemplate.queryForObject("SELECT id FROM products WHERE name LIKE ? AND active AND supplier_id = ?", String.class,
                padraoDoNome, Dados.idFornecedora());
    }

    private void ativarAddon(String companyId) {
        jdbcTemplate.update("INSERT INTO company_addons (id, company_id, addon_key, status, activated_at, valido_ate, created_at, updated_at) "
                        + "VALUES (?, ?, 'PO_ROBOT', 'ATIVO', now(), ?, now(), now())",
                UUID.randomUUID().toString(), companyId, java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(30))));
    }

    private PoRoboRegraDto regra(String productId, Integer quantidade, BigDecimal limite) {
        return poRoboService.criar(petroangolaId, new PoRoboRegraRequest(productId, "MANUAL", new BigDecimal("30"), "SEMANAL",
                quantidade == null ? null : IntNode.valueOf(quantidade),
                limite == null ? null : com.fasterxml.jackson.databind.node.DecimalNode.valueOf(limite), null), ADMIN);
    }

    private PoRoboRegra recarregar(String id) {
        entityManager.flush();
        entityManager.clear();
        return regraRepository.findById(id).orElseThrow();
    }

    private int contagem(String sql, Object... args) {
        entityManager.flush();
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private int pos() {
        return contagem("SELECT count(*) FROM purchase_orders WHERE buyer_company_id = ?", petroangolaId);
    }

    @Test
    void semAddonAtivoNaoSeConfiguraNemSeExecuta() {
        assertThatThrownBy(() -> regra(valvulaId, 2, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("add-on pago");
    }

    @Test
    void quantidadeFixaSobrepoeECalculoEscalaPelaPeriodicidade() {
        ativarAddon(petroangolaId);
        PoRoboRegra fixa = recarregar(regra(tubosId, 4, null).id());
        assertThat(poRoboService.resolverQuantidade(fixa)).isEqualTo(4);

        PoRoboRegra semanal = recarregar(regra(mangueiraId, null, null).id());
        assertThat(poRoboService.resolverQuantidade(semanal)).isEqualTo(7); // round(30 × 7/30)

        // Origem IA sem histórico de compra: média 0 → nunca devolve zero (mínimo 1 unidade).
        PoRoboRegraDto ia = poRoboService.criar(petroangolaId, new PoRoboRegraRequest(valvulaId, "IA", new BigDecimal("30"),
                "MENSAL", null, null, null), ADMIN);
        assertThat(poRoboService.resolverQuantidade(recarregar(ia.id()))).isEqualTo(1);
    }

    @Test
    void umaEmpresaSoTemUmaRegraPorProduto() {
        ativarAddon(petroangolaId);
        regra(valvulaId, 2, null);
        assertThatThrownBy(() -> regra(valvulaId, 3, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Já existe uma regra");
    }

    @Test
    void camposDeStockEPrazoSaoValidadosEGuardados() {
        ativarAddon(petroangolaId);
        var corpo = new PoRoboRegraRequest(valvulaId, "MANUAL", new BigDecimal("30"), "MENSAL", null, null, null,
                IntNode.valueOf(5), IntNode.valueOf(2), IntNode.valueOf(10));
        PoRoboRegraDto criada = poRoboService.criar(petroangolaId, corpo, ADMIN);
        assertThat(criada.stockMinimo()).isEqualTo(5);
        assertThat(criada.stockSeguranca()).isEqualTo(2);
        assertThat(criada.prazoEntregaDias()).isEqualTo(10);
        assertThat(criada.ultimaExecucaoEm()).isNull();
        assertThat(criada.product().supplier().name()).as("a fornecedora vai no resumo do produto").isNotBlank();

        // Ausente mantém; null explícito limpa.
        PoRoboRegraDto mantida = poRoboService.atualizar(petroangolaId, criada.id(),
                new PoRoboRegraRequest(null, null, new BigDecimal("40"), null, null, null, null), ADMIN);
        assertThat(mantida.stockMinimo()).isEqualTo(5);
        assertThat(mantida.prazoEntregaDias()).isEqualTo(10);
        PoRoboRegraDto limpa = poRoboService.atualizar(petroangolaId, criada.id(),
                new PoRoboRegraRequest(null, null, null, null, null, null, null,
                        com.fasterxml.jackson.databind.node.NullNode.getInstance(), null, null), ADMIN);
        assertThat(limpa.stockMinimo()).isNull();
        assertThat(limpa.stockSeguranca()).isEqualTo(2);

        assertThatThrownBy(() -> poRoboService.atualizar(petroangolaId, criada.id(),
                new PoRoboRegraRequest(null, null, null, null, null, null, null, IntNode.valueOf(-1), null, null), ADMIN))
                .isInstanceOf(ValidationException.class).hasMessageContaining("stockMinimo");
        assertThatThrownBy(() -> poRoboService.atualizar(petroangolaId, criada.id(),
                new PoRoboRegraRequest(null, null, null, null, null, null, null, null, null, IntNode.valueOf(0)), ADMIN))
                .isInstanceOf(ValidationException.class).hasMessageContaining("prazoEntregaDias");
    }

    @Test
    void oRobotCriaUmaPropostaPendenteENuncaUmaPO() {
        ativarAddon(petroangolaId);
        PoRoboRegra regra = recarregar(regra(valvulaId, 2, null).id());
        Instant antes = regra.getProximaExecucaoEm();
        int posAntes = pos();

        PoRoboService.ResultadoRegra resultado = poRoboService.executarRegra(regra, null);

        assertThat(resultado.aviso()).isNull();
        PoRoboProposta proposta = propostaRepository.findById(resultado.proposta().getId()).orElseThrow();
        assertThat(proposta.getEstado()).isEqualTo(EstadoProposta.PENDENTE_REVISAO);
        assertThat(proposta.getCompanyId()).isEqualTo(petroangolaId);
        assertThat(proposta.getSupplierId()).isEqualTo(kiandaId);
        assertThat(proposta.getProductId()).isEqualTo(valvulaId);
        assertThat(proposta.getQuantidade()).isEqualTo(2).isEqualTo(proposta.getQuantidadeOriginal());
        assertThat(proposta.isLimiteAplicado()).isFalse();
        assertThat(proposta.getValorTotal()).isEqualByComparingTo("1700000");
        assertThat(proposta.getPurchaseOrderId()).as("sem decisão humana não há PO").isNull();
        assertThat(PoRoboJson.lerPassos(proposta.getJustificacao())).isNotEmpty();
        assertThat(pos()).as("o robot nunca cria ordens de compra").isEqualTo(posAntes);
        assertThat(contagem("SELECT count(*) FROM audit_logs WHERE action = 'PO_CRIADA_ROBOT' AND company_id = ?", petroangolaId)).isZero();

        PoRoboPrevisao previsao = previsaoRepository.findById(proposta.getPrevisaoId()).orElseThrow();
        assertThat(previsao.getStockActual()).as("sem fonte fiável do stock do comprador").isNull();
        assertThat(previsao.getQuantidadeRecomendada()).isEqualTo(2);
        assertThat(previsao.getOrigemDados()).isEqualTo(PoRoboOrigemDados.MANUAL);
        assertThat(previsao.getConsumoMedioMensal()).isEqualByComparingTo("30");

        // Auditoria: o autor é o robot, com a empresa (a actividade filtra por empresa).
        assertThat(contagem("SELECT count(*) FROM audit_logs WHERE action = 'PO_ROBO_PROPOSTA_CRIADA' AND entity_id = ? AND actor_name = 'PO Robot' "
                + "AND actor_id IS NULL AND company_id = ? AND detail->>'regraId' = ?", proposta.getId(), petroangolaId, regra.getId())).isEqualTo(1);

        PoRoboRegra depois = regraRepository.findById(regra.getId()).orElseThrow();
        assertThat(depois.getProximaExecucaoEm()).isAfter(antes).isAfter(Instant.now());
        assertThat(depois.getUltimaExecucaoEm()).isNotNull();
    }

    @Test
    void previsaoUsaPrazoEStockDeSegurancaNoPontoDeEncomendaSemInventarioDoComprador() {
        ativarAddon(petroangolaId);
        var corpo = new PoRoboRegraRequest(valvulaId, "MANUAL", new BigDecimal("30"), "MENSAL", null, null, null,
                IntNode.valueOf(5), IntNode.valueOf(3), IntNode.valueOf(10));
        PoRoboRegra regra = recarregar(poRoboService.criar(petroangolaId, corpo, ADMIN).id());

        PoRoboService.ResultadoRegra resultado = poRoboService.executarRegra(regra, null);

        PoRoboPrevisao previsao = previsaoRepository.findById(resultado.proposta().getPrevisaoId()).orElseThrow();
        // 30/30 = 1 por dia × 10 dias + 3 de segurança.
        assertThat(previsao.getPontoEncomenda()).isEqualByComparingTo("13");
        assertThat(previsao.getStockMinimo()).isEqualTo(5);
        assertThat(previsao.getStockActual()).isNull();
        assertThat(resultado.proposta().getQuantidade()).as("a quantidade segue o consumo médio (mensal = 30)").isEqualTo(30);
    }

    @Test
    void oLimiteReduzAQuantidadeEGuardaAOriginal() {
        ativarAddon(petroangolaId);
        // 5 × 944,44 = 4.722,22 USD > 2.000 → cabem 2 unidades (1.888,89 USD).
        PoRoboRegra regra = recarregar(regra(valvulaId, 5, new BigDecimal("2000")).id());

        PoRoboService.ResultadoRegra resultado = poRoboService.executarRegra(regra, null);

        PoRoboProposta proposta = propostaRepository.findById(resultado.proposta().getId()).orElseThrow();
        assertThat(proposta.getQuantidade()).isEqualTo(2);
        assertThat(proposta.getQuantidadeOriginal()).isEqualTo(5);
        assertThat(proposta.isLimiteAplicado()).isTrue();
        assertThat(proposta.getQuantidade()).as("nunca zero ou negativa").isPositive();
        assertThat(PoRoboJson.lerPassos(proposta.getJustificacao())).extracting("rotulo").contains("Limite de segurança", "Quantidade reduzida pelo limite");
    }

    @Test
    void seNemUmaUnidadeCabeNoLimiteNaoHaPropostaEOMotivoFicaNaExecucao() {
        ativarAddon(petroangolaId);
        PoRoboRegra regra = recarregar(regra(valvulaId, 2, new BigDecimal("1")).id());
        Instant antes = regra.getProximaExecucaoEm();
        int posAntes = pos();

        PoRoboService.ResultadoRegra resultado = poRoboService.executarRegra(regra, null);

        assertThat(resultado.proposta()).isNull();
        assertThat(resultado.aviso()).contains("limite de segurança").contains("sequer 1 unidade").contains("Válvula");
        assertThat(contagem("SELECT count(*) FROM po_robo_propostas WHERE company_id = ?", petroangolaId)).isZero();
        assertThat(pos()).isEqualTo(posAntes);
        // Fica a previsão (recomendação 0) e a regra avança: o resultado é determinístico, não se repete todos os dias.
        assertThat(contagem("SELECT count(*) FROM po_robo_previsoes WHERE regra_id = ? AND quantidade_recomendada = 0", regra.getId())).isEqualTo(1);
        assertThat(regraRepository.findById(regra.getId()).orElseThrow().getProximaExecucaoEm()).isAfter(antes);
    }

    @Test
    void produtoInativoFalha() {
        ativarAddon(petroangolaId);
        PoRoboRegra ok = recarregar(regra(valvulaId, 2, null).id());
        jdbcTemplate.update("UPDATE products SET active = false WHERE id = ?", valvulaId);
        entityManager.clear();
        assertThatThrownBy(() -> poRoboService.executarRegra(ok, null)).hasMessageContaining("não existe ou está inativo");
    }

    @Test
    void umaPropostaNovaExpiraAPendenteAnteriorDaMesmaRegra() {
        ativarAddon(petroangolaId);
        PoRoboRegra regra = recarregar(regra(valvulaId, 2, null).id());

        String primeira = poRoboService.executarRegra(regra, null).proposta().getId();
        String segunda = poRoboService.executarRegra(regraRepository.findById(regra.getId()).orElseThrow(), null).proposta().getId();

        entityManager.flush();
        entityManager.clear();
        PoRoboProposta antiga = propostaRepository.findById(primeira).orElseThrow();
        assertThat(antiga.getEstado()).isEqualTo(EstadoProposta.EXPIRADA);
        assertThat(antiga.getExpiradaEm()).isNotNull();
        assertThat(antiga.getMotivoExpiracao()).contains("Substituída");
        assertThat(propostaRepository.findById(segunda).orElseThrow().getEstado()).isEqualTo(EstadoProposta.PENDENTE_REVISAO);
        assertThat(propostaRepository.findByRegraIdAndEstado(regra.getId(), EstadoProposta.PENDENTE_REVISAO)).hasSize(1);
        assertThat(contagem("SELECT count(*) FROM audit_logs WHERE action = 'PO_ROBO_PROPOSTA_EXPIRADA' AND entity_id = ? AND company_id = ?",
                primeira, petroangolaId)).isEqualTo(1);
    }

    @Test
    void aBaseGaranteNoMaximoUmaPropostaPendentePorRegra() {
        ativarAddon(petroangolaId);
        PoRoboRegra regra = recarregar(regra(valvulaId, 2, null).id());
        poRoboService.executarRegra(regra, null);
        entityManager.flush();
        // Inserir à mão uma segunda pendente da mesma regra viola o índice único parcial.
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO po_robo_propostas (id, company_id, regra_id, product_id, supplier_id, quantidade, "
                        + "quantidade_original, moeda, estado, criada_em, updated_at) VALUES (?, ?, ?, ?, ?, 1, 1, 'AOA', 'PENDENTE_REVISAO', now(), now())",
                UUID.randomUUID().toString(), petroangolaId, regra.getId(), valvulaId, kiandaId))
                .hasMessageContaining("po_robo_propostas_regra_pendente_uq");
    }

    @Test
    void oCicloCorreSoAsDevidasNaoAbortaComUmaFalhaENaoDuplica() {
        ativarAddon(petroangolaId);
        String devida = regra(valvulaId, 2, null).id();
        String falha = regra(mangueiraId, 2, null).id(); // produto inativo → falha técnica
        String semLugar = regra(tubosId, 2, new BigDecimal("1")).id(); // nem 1 unidade no limite → erro registado, sem falha
        String inativa = regra(produto("Gerador Diesel%"), 2, null).id();
        String futura = regra(produto("Inspeção por Ultrassom%"), 2, null).id();
        entityManager.flush();
        int posAntes = pos();
        jdbcTemplate.update("UPDATE products SET active = false WHERE id = ?", mangueiraId);
        jdbcTemplate.update("UPDATE po_robo_regras SET ativo = false WHERE id = ?", inativa);
        jdbcTemplate.update("UPDATE po_robo_regras SET proxima_execucao_em = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(3))), futura);
        entityManager.flush();
        entityManager.clear();

        PoRoboService.CicloResultado r = poRoboService.executarCiclo();
        assertThat(r.total()).isEqualTo(3);
        assertThat(r.criadas()).isEqualTo(1);
        assertThat(r.falhas()).extracting(PoRoboService.Falha::regraId).containsExactly(falha);
        assertThat(r.falhas().get(0).erro()).contains("não existe ou está inativo");

        assertThat(propostasDaRegra(devida)).isEqualTo(1);
        assertThat(propostasDaRegra(falha)).isZero();
        assertThat(propostasDaRegra(semLugar)).isZero();
        assertThat(propostasDaRegra(inativa)).isZero();
        assertThat(propostasDaRegra(futura)).isZero();
        assertThat(pos()).as("um ciclo nunca cria POs").isEqualTo(posAntes);

        // A que falhou volta a ficar devida (data original reposta); a criada e a sem-lugar avançaram.
        assertThat(regraRepository.findById(falha).orElseThrow().getProximaExecucaoEm()).isBefore(Instant.now());
        assertThat(regraRepository.findById(devida).orElseThrow().getProximaExecucaoEm()).isAfter(Instant.now());
        assertThat(regraRepository.findById(semLugar).orElseThrow().getProximaExecucaoEm()).isAfter(Instant.now());

        // Uma execução para a empresa, com os números e os erros certos.
        List<PoRoboExecucao> execucoes = execucaoRepository.findByCompanyIdOrderByIniciadaEmDesc(petroangolaId,
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(execucoes).hasSize(1);
        PoRoboExecucao exec = execucoes.get(0);
        assertThat(exec.getEstado()).isEqualTo(EstadoExecucao.COM_ERROS);
        assertThat(exec.getOrigem()).isEqualTo("AGENDADA");
        assertThat(exec.getRegrasAvaliadas()).isEqualTo(3);
        assertThat(exec.getPropostasCriadas()).isEqualTo(1);
        assertThat(exec.getTerminadaEm()).isNotNull();
        assertThat(PoRoboJson.lerTextos(exec.getErros())).hasSize(2)
                .anyMatch(e -> e.contains("sequer 1 unidade")).anyMatch(e -> e.contains("não existe ou está inativo"));
        assertThat(contagem("SELECT count(*) FROM po_robo_propostas WHERE execucao_id = ?", exec.getId())).isEqualTo(1);

        // Segunda corrida seguida: só a que falhou volta a ser tentada; a criada não se duplica.
        PoRoboService.CicloResultado r2 = poRoboService.executarCiclo();
        assertThat(r2.total()).isEqualTo(1);
        assertThat(r2.criadas()).isZero();
        assertThat(propostasDaRegra(devida)).isEqualTo(1);
        List<PoRoboExecucao> depois = execucaoRepository.findByCompanyIdOrderByIniciadaEmDesc(petroangolaId,
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(depois).hasSize(2);
        assertThat(depois.get(0).getEstado()).as("só erros, nenhuma proposta").isEqualTo(EstadoExecucao.FALHOU);
    }

    @Test
    void umaExecucaoSemErrosFicaConcluida() {
        ativarAddon(petroangolaId);
        regra(valvulaId, 2, null);
        entityManager.flush();
        entityManager.clear();

        PoRoboService.CicloResultado r = poRoboService.executarCiclo();

        assertThat(r.criadas()).isEqualTo(1);
        PoRoboExecucao exec = execucaoRepository.findByCompanyIdOrderByIniciadaEmDesc(petroangolaId,
                org.springframework.data.domain.PageRequest.of(0, 1)).get(0);
        assertThat(exec.getEstado()).isEqualTo(EstadoExecucao.CONCLUIDA);
        assertThat(PoRoboJson.lerTextos(exec.getErros())).isEmpty();
    }

    @Test
    void asExecucoesSaoPorEmpresaEOCicloNuncaMisturaDados() {
        ativarAddon(petroangolaId);
        regra(valvulaId, 2, null);
        entityManager.flush();
        entityManager.clear();

        poRoboService.executarCiclo();

        assertThat(execucaoRepository.findByCompanyIdOrderByIniciadaEmDesc(kiandaId, org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();
        assertThat(contagem("SELECT count(*) FROM po_robo_propostas WHERE company_id = ?", kiandaId)).isZero();
    }

    private Integer propostasDaRegra(String regraId) {
        return contagem("SELECT count(*) FROM po_robo_propostas WHERE regra_id = ?", regraId);
    }
}
