package ao.kixima.porobo;

import ao.kixima.addon.AddonService;
import ao.kixima.analytics.CategoryAnalyticsService;
import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.catalog.Product;
import ao.kixima.catalog.ProductRepository;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.common.money.FxService;
import ao.kixima.ops.OperationalAlertService;
import ao.kixima.porobo.dto.PoRoboMediaSugeridaDto;
import ao.kixima.porobo.dto.PoRoboPassoCalculo;
import ao.kixima.porobo.dto.PoRoboRegraDto;
import ao.kixima.porobo.dto.PoRoboRegraRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Espelha backend/src/services/poRoboService.js + poRoboRoutes.js —
 * Automatic PO Robot (add-on PRO, pago): PREPARA PROPOSTAS de compra
 * periodicamente, com base na média mensal de compra por produto.
 *
 * <p>O robot NUNCA cria ordens de compra, nem aprova, nem paga, nem submete: a
 * PO só nasce quando uma pessoa submete a proposta
 * ({@link PoRoboPropostaService#submeter}), pelo {@code PoService} TAL E QUAL
 * (sem via paralela), em AGUARDANDO_APROVACAO. Aqui só se configuram as regras
 * e se corre o ciclo agendado, que grava execução, previsão e proposta.
 *
 * <p>Transações: cada regra corre na SUA transação (TransactionTemplate), tal
 * como no Node cada regra é independente — uma regra que falha não pode
 * arrastar consigo as propostas já criadas pelas outras no mesmo ciclo.
 *
 * <p>Stock: não existe fonte fiável do inventário do comprador, por isso
 * {@code stockActual} é sempre {@code null} e a quantidade segue o consumo médio.
 * O stock do fornecedor nunca é usado como stock do comprador.
 */
@Service
public class PoRoboService {

    private static final Logger log = LoggerFactory.getLogger(PoRoboService.class);

    /** Janela da reserva de uma regra durante a execução — só tem de ser maior do que o tempo que uma execução demora. */
    static final long RESERVA_MS = 10 * 60 * 1000;

    private static final Map<PoRoboPeriodicidade, BigDecimal> FRACAO_DO_MES = Map.of(
            PoRoboPeriodicidade.SEMANAL, BigDecimal.valueOf(7).divide(BigDecimal.valueOf(30), 10, RoundingMode.HALF_UP),
            PoRoboPeriodicidade.QUINZENAL, BigDecimal.valueOf(14).divide(BigDecimal.valueOf(30), 10, RoundingMode.HALF_UP),
            PoRoboPeriodicidade.MENSAL, BigDecimal.ONE);
    private static final Map<PoRoboPeriodicidade, Integer> DIAS_ATE_PROXIMA = Map.of(
            PoRoboPeriodicidade.SEMANAL, 7, PoRoboPeriodicidade.QUINZENAL, 14, PoRoboPeriodicidade.MENSAL, 30);

    /** Meses de histórico de compras que alimentam a média sugerida (os mesmos de {@code mediaMensalPorProduto}). */
    static final int PERIODO_MESES = 12;

    public record Falha(String regraId, String companyId, String erro) {
    }

    /** {@code criadas} = propostas criadas neste ciclo (nunca POs). */
    public record CicloResultado(int total, int criadas, List<Falha> falhas) {
    }

    /** Resultado de avaliar uma regra: ou nasce uma proposta, ou há um aviso a registar na execução (nunca ambos). */
    public record ResultadoRegra(PoRoboProposta proposta, String aviso) {
    }

    private record MediaUsada(BigDecimal media, PoRoboOrigemDados origem) {
    }

    private record Calculo(MediaUsada media, int quantidadeCalculada, int quantidadeFinal, boolean limiteAplicado,
                           BigDecimal pontoEncomenda, BigDecimal precoUnitarioUsd, List<PoRoboPassoCalculo> passos) {
    }

    private final PoRoboRegraRepository regraRepository;
    private final PoRoboExecucaoRepository execucaoRepository;
    private final PoRoboPrevisaoRepository previsaoRepository;
    private final PoRoboPropostaRepository propostaRepository;
    private final ProductRepository productRepository;
    private final AddonService addonService;
    private final CategoryAnalyticsService categoryAnalyticsService;
    private final FxService fxService;
    private final AuditService auditService;
    private final OperationalAlertService operationalAlertService;
    private final TransactionTemplate transactionTemplate;

    public PoRoboService(PoRoboRegraRepository regraRepository, PoRoboExecucaoRepository execucaoRepository,
                          PoRoboPrevisaoRepository previsaoRepository, PoRoboPropostaRepository propostaRepository,
                          ProductRepository productRepository, AddonService addonService,
                          CategoryAnalyticsService categoryAnalyticsService, FxService fxService,
                          AuditService auditService, OperationalAlertService operationalAlertService,
                          PlatformTransactionManager transactionManager) {
        this.regraRepository = regraRepository;
        this.execucaoRepository = execucaoRepository;
        this.previsaoRepository = previsaoRepository;
        this.propostaRepository = propostaRepository;
        this.productRepository = productRepository;
        this.addonService = addonService;
        this.categoryAnalyticsService = categoryAnalyticsService;
        this.fxService = fxService;
        this.auditService = auditService;
        this.operationalAlertService = operationalAlertService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** O robot não é uma pessoa: o autor é «PO Robot», sem utilizador, mas com a empresa — a actividade filtra por empresa. */
    static Actor atorRobot(String companyId) {
        return new Actor(null, "PO Robot", null, companyId, null);
    }

    // --- Execução (job) -------------------------------------------------------

    static Instant proximaExecucao(PoRoboPeriodicidade periodicidade, Instant base) {
        int dias = DIAS_ATE_PROXIMA.getOrDefault(periodicidade, 30);
        return base.plus(dias, ChronoUnit.DAYS);
    }

    private MediaUsada mediaUsada(PoRoboRegra regra) {
        if (regra.getMediaOrigem() == PoRoboMediaOrigem.IA) {
            BigDecimal historica = categoryAnalyticsService.mediaMensalPorProduto(regra.getCompanyId(), regra.getProductId()).mediaMensal();
            return new MediaUsada(historica, PoRoboOrigemDados.HISTORICO_COMPRAS);
        }
        return new MediaUsada(regra.getMediaMensal(), PoRoboOrigemDados.MANUAL);
    }

    private static int quantidadeDoCiclo(PoRoboRegra regra, BigDecimal mediaMensal) {
        if (regra.getQuantidade() != null) return regra.getQuantidade();
        BigDecimal fracao = FRACAO_DO_MES.getOrDefault(regra.getPeriodicidade(), BigDecimal.ONE);
        return Math.max(1, mediaMensal.multiply(fracao).setScale(0, RoundingMode.HALF_UP).intValue());
    }

    /**
     * A quantidade a pedir NESTA execução — a média aceite/definida pelo
     * cliente, escalada para a periodicidade. {@code quantidade} (fixa)
     * sobrepõe o cálculo quando o cliente a definiu à mão. Antes do limite de segurança.
     */
    public int resolverQuantidade(PoRoboRegra regra) {
        if (regra.getQuantidade() != null) return regra.getQuantidade();
        return quantidadeDoCiclo(regra, mediaUsada(regra).media());
    }

    private static String fmt(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    private BigDecimal valorUsd(Product product, int quantidade) {
        return fxService.toUsd(product.getUnitPrice().multiply(BigDecimal.valueOf(quantidade)), product.getCurrency());
    }

    /** Previsão e justificação passo a passo; aplica o limite de segurança reduzindo a quantidade (nunca ultrapassa o limite). */
    private Calculo calcular(PoRoboRegra regra, Product product) {
        MediaUsada media = mediaUsada(regra);
        List<PoRoboPassoCalculo> passos = new ArrayList<>();
        passos.add(new PoRoboPassoCalculo("Origem do consumo", null, media.origem() == PoRoboOrigemDados.MANUAL
                ? "Média mensal definida na regra" : "Histórico de compras dos últimos " + PERIODO_MESES + " meses"));
        passos.add(new PoRoboPassoCalculo("Consumo médio mensal", null, fmt(media.media()) + " un"));

        int calculada = quantidadeDoCiclo(regra, media.media());
        if (regra.getQuantidade() != null) {
            passos.add(new PoRoboPassoCalculo("Quantidade fixa da regra", "sobrepõe o cálculo", calculada + " un"));
        } else {
            int dias = DIAS_ATE_PROXIMA.getOrDefault(regra.getPeriodicidade(), 30);
            passos.add(new PoRoboPassoCalculo("Quantidade do ciclo " + regra.getPeriodicidade().name().toLowerCase(),
                    "média mensal × " + dias + "/30 (mínimo 1)", calculada + " un"));
        }

        BigDecimal pontoEncomenda = null;
        if (regra.getPrazoEntregaDias() != null) {
            BigDecimal consumoDiario = media.media().divide(BigDecimal.valueOf(30), 6, RoundingMode.HALF_UP);
            int seguranca = regra.getStockSeguranca() == null ? 0 : regra.getStockSeguranca();
            pontoEncomenda = consumoDiario.multiply(BigDecimal.valueOf(regra.getPrazoEntregaDias()))
                    .add(BigDecimal.valueOf(seguranca)).setScale(3, RoundingMode.HALF_UP);
            passos.add(new PoRoboPassoCalculo("Ponto de encomenda", "consumo diário × prazo de entrega + stock de segurança",
                    fmt(pontoEncomenda) + " un"));
        }
        passos.add(new PoRoboPassoCalculo("Stock actual do comprador", null,
                "Desconhecido — sem fonte fiável do inventário; a quantidade segue o consumo médio"));

        int finalQ = calculada;
        boolean limiteAplicado = false;
        BigDecimal precoUsd = fxService.toUsd(product.getUnitPrice(), product.getCurrency());
        BigDecimal limite = regra.getLimiteMaximoUsd();
        if (limite != null) {
            passos.add(new PoRoboPassoCalculo("Limite de segurança", "quantidade × preço unitário ≤ limite", fmt(limite) + " USD"));
            if (precoUsd.signum() > 0 && valorUsd(product, calculada).compareTo(limite) > 0) {
                int maximo = limite.divide(precoUsd, 0, RoundingMode.FLOOR).min(BigDecimal.valueOf(calculada)).intValue();
                while (maximo > 0 && valorUsd(product, maximo).compareTo(limite) > 0) maximo--;
                finalQ = maximo;
                limiteAplicado = true;
                passos.add(new PoRoboPassoCalculo("Quantidade reduzida pelo limite", "⌊limite ÷ preço unitário (USD)⌋",
                        maximo + " un (calculadas: " + calculada + ")"));
            }
        }
        return new Calculo(media, calculada, finalQ, limiteAplicado, pontoEncomenda, precoUsd, passos);
    }

    /**
     * Uma regra, um ciclo: calcula a previsão, aplica o limite de segurança (reduz a quantidade; se nem uma unidade
     * couber, NÃO há proposta e devolve-se o motivo), expira a proposta pendente anterior da mesma regra e cria a nova
     * proposta (PENDENTE_REVISAO). Nunca cria PO. Avança a próxima execução — só depois de tudo gravado, para não
     * duplicar se o job correr duas vezes seguidas.
     */
    @Transactional
    public ResultadoRegra executarRegra(PoRoboRegra regra, String execucaoId) {
        addonService.assertAddon(regra.getCompanyId(), AddonService.PO_ROBOT, "Automatic PO Robot");

        Product product = productRepository.findById(regra.getProductId()).orElse(null);
        if (product == null || !product.isActive()) {
            throw new IllegalStateException("Produto " + regra.getProductId() + " não existe ou está inativo — regra desativada.");
        }

        Instant agora = Instant.now();
        Calculo calculo = calcular(regra, product);

        PoRoboPrevisao previsao = new PoRoboPrevisao(UUID.randomUUID().toString(), regra.getCompanyId(), regra.getId(),
                product.getId(), execucaoId, agora, calculo.media().media().setScale(3, RoundingMode.HALF_UP), regra.getStockMinimo(),
                regra.getStockSeguranca(), regra.getPrazoEntregaDias(), calculo.pontoEncomenda(), calculo.quantidadeFinal(),
                calculo.media().origem(), PoRoboJson.escrever(calculo.passos()));
        previsaoRepository.save(previsao);

        Instant proxima = proximaExecucao(regra.getPeriodicidade(), agora);

        if (calculo.quantidadeFinal() < 1) {
            // Nem uma unidade cabe no limite: sem proposta. O resultado é determinístico, por isso a regra avança (não se repete todos os dias).
            String aviso = "Regra de «" + product.getName() + "»: o limite de segurança de " + fmt(regra.getLimiteMaximoUsd())
                    + " USD não permite sequer 1 unidade (preço unitário " + fmt(calculo.precoUnitarioUsd()) + " USD) — proposta não criada.";
            regraRepository.concluirExecucao(regra.getId(), agora, proxima);
            return new ResultadoRegra(null, aviso);
        }

        // No máximo uma proposta pendente por regra: a nova expira a anterior. Flush antes do INSERT (índice único parcial).
        List<PoRoboProposta> pendentes = propostaRepository.findByRegraIdAndEstado(regra.getId(), EstadoProposta.PENDENTE_REVISAO);
        for (PoRoboProposta anterior : pendentes) {
            String motivo = "Substituída por uma proposta mais recente da mesma regra, criada em "
                    + agora.atZone(ZoneOffset.UTC).toLocalDate() + ".";
            anterior.expirar(agora, motivo);
            auditService.recordSafe(new AuditService.Entry(atorRobot(regra.getCompanyId()), PoRoboAuditoria.PROPOSTA_EXPIRADA,
                    PoRoboAuditoria.ENTIDADE_PROPOSTA, anterior.getId(), product.getName(),
                    mapa("produto", product.getName(), "regraId", regra.getId(), "motivo", motivo)));
        }
        propostaRepository.saveAllAndFlush(pendentes);

        PoRoboProposta proposta = new PoRoboProposta(UUID.randomUUID().toString(), regra.getCompanyId(), regra.getId(),
                previsao.getId(), execucaoId, product.getId(), product.getSupplierId(), calculo.quantidadeFinal(),
                calculo.quantidadeCalculada(), product.getUnitPrice(), product.getCurrency(),
                PoRoboJson.escrever(calculo.passos()), calculo.limiteAplicado(), agora);
        propostaRepository.saveAndFlush(proposta);

        auditService.recordSafe(new AuditService.Entry(atorRobot(regra.getCompanyId()), PoRoboAuditoria.PROPOSTA_CRIADA,
                PoRoboAuditoria.ENTIDADE_PROPOSTA, proposta.getId(), product.getName(),
                mapa("produto", product.getName(), "regraId", regra.getId(), "quantidade", proposta.getQuantidade(),
                        "quantidadeOriginal", proposta.getQuantidadeOriginal(), "limiteAplicado", proposta.isLimiteAplicado())));

        regraRepository.concluirExecucao(regra.getId(), agora, proxima);
        return new ResultadoRegra(proposta, null);
    }

    private static Map<String, Object> mapa(Object... paresChaveValor) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < paresChaveValor.length; i += 2) m.put((String) paresChaveValor[i], paresChaveValor[i + 1]);
        return m;
    }

    private String nomeDoProduto(PoRoboRegra regra) {
        try {
            return productRepository.findById(regra.getProductId()).map(Product::getName).orElse(regra.getProductId());
        } catch (Exception e) {
            return regra.getProductId();
        }
    }

    /**
     * Corre todas as regras ativas cuja vez chegou, agrupadas por empresa (uma execução por empresa). Uma regra que
     * falha (produto descontinuado, empresa sem add-on ativo…) NÃO aborta o ciclo inteiro — regista o erro e segue para
     * a regra seguinte. Só cria propostas: nenhuma PO.
     */
    public CicloResultado executarCiclo() {
        List<PoRoboRegra> regras = transactionTemplate.execute(status ->
                regraRepository.findByAtivoTrueAndProximaExecucaoEmLessThanEqual(Instant.now()));

        int criadas = 0;
        List<Falha> falhas = new ArrayList<>();

        Map<String, List<PoRoboRegra>> porEmpresa = regras.stream()
                .collect(Collectors.groupingBy(PoRoboRegra::getCompanyId, LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<PoRoboRegra>> entrada : porEmpresa.entrySet()) {
            String companyId = entrada.getKey();

            // Reserva atómica por regra: só as que esta corrida conseguiu reclamar entram na execução da empresa.
            Map<PoRoboRegra, Instant> reclamadas = new LinkedHashMap<>();
            for (PoRoboRegra regra : entrada.getValue()) {
                Instant lida = regra.getProximaExecucaoEm();
                Integer reservadas = transactionTemplate.execute(status ->
                        regraRepository.reclamar(regra.getId(), lida, Instant.now().plusMillis(RESERVA_MS)));
                if (reservadas != null && reservadas == 1) reclamadas.put(regra, lida);
            }
            if (reclamadas.isEmpty()) continue; // outra corrida já reclamou estas regras

            String execucaoId = UUID.randomUUID().toString();
            Instant inicio = Instant.now();
            transactionTemplate.executeWithoutResult(status ->
                    execucaoRepository.save(new PoRoboExecucao(execucaoId, companyId, inicio)));

            int propostasDaEmpresa = 0;
            List<String> erros = new ArrayList<>();

            for (Map.Entry<PoRoboRegra, Instant> r : reclamadas.entrySet()) {
                PoRoboRegra regra = r.getKey();
                Instant lida = r.getValue();
                try {
                    ResultadoRegra resultado = transactionTemplate.execute(status -> executarRegra(regra, execucaoId));
                    if (resultado != null && resultado.proposta() != null) {
                        propostasDaEmpresa++;
                    } else if (resultado != null && resultado.aviso() != null) {
                        erros.add(resultado.aviso());
                    }
                } catch (Exception err) {
                    // Falhou depois de reservada — repõe a data original para o ciclo seguinte tentar de novo.
                    try {
                        transactionTemplate.executeWithoutResult(status -> regraRepository.definirProximaExecucao(regra.getId(), lida));
                    } catch (Exception ignorado) {
                        // mesmo `.catch(() => {})` do Node — a falha original é a que importa
                    }
                    falhas.add(new Falha(regra.getId(), regra.getCompanyId(), err.getMessage()));
                    erros.add("Regra de «" + nomeDoProduto(regra) + "»: " + err.getMessage());
                    log.warn("poRoboService: falha ao executar regra {}: {}", regra.getId(), err.getMessage());
                    operationalAlertService.avisarFalha("PO_ROBOT", "Falha ao preparar proposta automática (regra " + regra.getId() + ")",
                            err.getMessage());
                }
            }

            criadas += propostasDaEmpresa;
            terminarExecucao(execucaoId, companyId, reclamadas.size(), propostasDaEmpresa, erros);
        }

        return new CicloResultado(regras.size(), criadas, falhas);
    }

    private void terminarExecucao(String execucaoId, String companyId, int avaliadas, int propostas, List<String> erros) {
        EstadoExecucao estado = erros.isEmpty() ? EstadoExecucao.CONCLUIDA
                : (propostas > 0 ? EstadoExecucao.COM_ERROS : EstadoExecucao.FALHOU);
        transactionTemplate.executeWithoutResult(status -> {
            PoRoboExecucao execucao = execucaoRepository.findById(execucaoId).orElseThrow();
            execucao.terminar(Instant.now(), estado, avaliadas, propostas, PoRoboJson.escrever(erros));
            execucaoRepository.save(execucao);
            auditService.recordSafe(new AuditService.Entry(atorRobot(companyId), PoRoboAuditoria.EXECUCAO,
                    PoRoboAuditoria.ENTIDADE_EXECUCAO, execucaoId, null,
                    mapa("estado", estado.name(), "regrasAvaliadas", avaliadas, "propostasCriadas", propostas, "erros", erros.size())));
        });
    }

    // --- Configuração das regras (poRoboRoutes.js) -----------------------------

    @Transactional(readOnly = true)
    public List<PoRoboRegraDto> listar(String companyId) {
        return regraRepository.findByCompanyIdComProduto(companyId).stream().map(r -> PoRoboRegraDto.from(r, true)).toList();
    }

    @Transactional(readOnly = true)
    public PoRoboMediaSugeridaDto mediaSugerida(String companyId, String productId) {
        addonService.assertAddon(companyId, AddonService.PO_ROBOT);
        return PoRoboMediaSugeridaDto.de(categoryAnalyticsService.mediaMensalPorProduto(companyId, productId), PERIODO_MESES);
    }

    private record CorpoValidado(String productId, PoRoboMediaOrigem mediaOrigem, BigDecimal mediaMensal,
                                 PoRoboPeriodicidade periodicidade, Integer quantidade, BigDecimal limiteMaximoUsd,
                                 Integer stockMinimo, Integer stockSeguranca, Integer prazoEntregaDias) {
    }

    private static PoRoboMediaOrigem parseOrigem(String v) {
        try {
            return v == null ? null : PoRoboMediaOrigem.valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static PoRoboPeriodicidade parsePeriodicidade(String v) {
        try {
            return v == null ? null : PoRoboPeriodicidade.valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean presente(JsonNode n) {
        return n != null; // JSON null vem como NullNode (presente e a limpar); ausente vem como null Java
    }

    private static JsonNode comoNo(Integer v) {
        return v == null ? null : IntNode.valueOf(v);
    }

    private static Integer inteiroOpcional(JsonNode n, String campo, int minimo) {
        if (n == null || n.isNull()) return null;
        if (!n.isNumber() || !n.isIntegralNumber() || !n.canConvertToInt() || n.asInt() < minimo) {
            throw new ValidationException(campo + " tem de ser um número inteiro " + (minimo == 0 ? "não negativo." : "maior que zero."));
        }
        return n.asInt();
    }

    private CorpoValidado validarCorpo(String productId, PoRoboMediaOrigem mediaOrigem, BigDecimal mediaMensal,
                                       PoRoboPeriodicidade periodicidade, JsonNode quantidade, JsonNode limiteMaximoUsd,
                                       JsonNode stockMinimo, JsonNode stockSeguranca, JsonNode prazoEntregaDias) {
        if (productId == null || productId.isBlank()) throw new ValidationException("Indique o produto.");
        if (mediaOrigem == null) throw new ValidationException("mediaOrigem inválida — use IA ou MANUAL.");
        if (mediaMensal == null || mediaMensal.signum() <= 0) throw new ValidationException("mediaMensal tem de ser maior que zero.");
        if (periodicidade == null) throw new ValidationException("periodicidade inválida — use SEMANAL, QUINZENAL, MENSAL.");

        Integer qtd = null;
        if (quantidade != null && !quantidade.isNull()) {
            if (!quantidade.canConvertToInt() || quantidade.asInt() <= 0 || (quantidade.isNumber() && !quantidade.isIntegralNumber())) {
                throw new ValidationException("quantidade tem de ser um número inteiro maior que zero.");
            }
            qtd = quantidade.asInt();
        }
        BigDecimal limite = null;
        if (limiteMaximoUsd != null && !limiteMaximoUsd.isNull()) {
            if (!limiteMaximoUsd.isNumber() || limiteMaximoUsd.decimalValue().signum() <= 0) {
                throw new ValidationException("limiteMaximoUsd tem de ser maior que zero.");
            }
            limite = limiteMaximoUsd.decimalValue();
        }
        return new CorpoValidado(productId, mediaOrigem, mediaMensal, periodicidade, qtd, limite,
                inteiroOpcional(stockMinimo, "stockMinimo", 0), inteiroOpcional(stockSeguranca, "stockSeguranca", 0),
                inteiroOpcional(prazoEntregaDias, "prazoEntregaDias", 1));
    }

    @Transactional
    public PoRoboRegraDto criar(String companyId, PoRoboRegraRequest body, Actor actor) {
        addonService.assertAddon(companyId, AddonService.PO_ROBOT);
        CorpoValidado dados = validarCorpo(body.productId(), parseOrigem(body.mediaOrigem()), body.mediaMensal(),
                parsePeriodicidade(body.periodicidade()), body.quantidade(), body.limiteMaximoUsd(),
                body.stockMinimo(), body.stockSeguranca(), body.prazoEntregaDias());

        Product produto = productRepository.findById(dados.productId()).orElseThrow(() -> new NotFoundException("Produto"));
        if (regraRepository.existsByCompanyIdAndProductId(companyId, dados.productId())) {
            throw new ConflictException("Já existe uma regra do PO Robot para este produto.");
        }

        // Elegível já na próxima corrida do job — a periodicidade rege o INTERVALO entre execuções, não um atraso antes da primeira.
        PoRoboRegra regra = new PoRoboRegra(UUID.randomUUID().toString(), companyId, dados.productId(), dados.mediaOrigem(),
                dados.mediaMensal(), dados.periodicidade(), dados.quantidade(), dados.limiteMaximoUsd(), Instant.now(), Instant.now());
        regra.setStockMinimo(dados.stockMinimo());
        regra.setStockSeguranca(dados.stockSeguranca());
        regra.setPrazoEntregaDias(dados.prazoEntregaDias());
        regraRepository.save(regra);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("produto", produto.getName());
        detail.put("productId", dados.productId());
        detail.put("mediaOrigem", dados.mediaOrigem().name());
        detail.put("mediaMensal", dados.mediaMensal());
        detail.put("periodicidade", dados.periodicidade().name());
        detail.put("quantidade", dados.quantidade());
        detail.put("limiteMaximoUsd", dados.limiteMaximoUsd());
        detail.put("stockMinimo", dados.stockMinimo());
        detail.put("stockSeguranca", dados.stockSeguranca());
        detail.put("prazoEntregaDias", dados.prazoEntregaDias());
        auditService.recordSafe(new AuditService.Entry(actor, PoRoboAuditoria.REGRA_CRIADA, PoRoboAuditoria.ENTIDADE_REGRA,
                regra.getId(), produto.getName(), detail));

        return PoRoboRegraDto.from(regra, PoRoboRegraDto.ProductRef.de(produto));
    }

    @Transactional
    public PoRoboRegraDto atualizar(String companyId, String id, PoRoboRegraRequest body, Actor actor) {
        addonService.assertAddon(companyId, AddonService.PO_ROBOT);
        PoRoboRegra existente = regraRepository.findById(id).orElse(null);
        if (existente == null || !existente.getCompanyId().equals(companyId)) throw new NotFoundException("Regra");

        Map<String, Object> data = new LinkedHashMap<>();
        if (body.ativo() != null) {
            existente.setAtivo(body.ativo());
            data.put("ativo", body.ativo());
        }
        boolean mexeNaRegra = body.mediaOrigem() != null || body.mediaMensal() != null || body.periodicidade() != null
                || presente(body.quantidade()) || presente(body.limiteMaximoUsd())
                || presente(body.stockMinimo()) || presente(body.stockSeguranca()) || presente(body.prazoEntregaDias());
        if (mexeNaRegra) {
            JsonNode quantidade = presente(body.quantidade()) ? body.quantidade()
                    : (existente.getQuantidade() == null ? null : IntNode.valueOf(existente.getQuantidade()));
            JsonNode limite = presente(body.limiteMaximoUsd()) ? body.limiteMaximoUsd()
                    : (existente.getLimiteMaximoUsd() == null ? null : DecimalNode.valueOf(existente.getLimiteMaximoUsd()));
            JsonNode stockMinimo = presente(body.stockMinimo()) ? body.stockMinimo() : comoNo(existente.getStockMinimo());
            JsonNode stockSeguranca = presente(body.stockSeguranca()) ? body.stockSeguranca() : comoNo(existente.getStockSeguranca());
            JsonNode prazo = presente(body.prazoEntregaDias()) ? body.prazoEntregaDias() : comoNo(existente.getPrazoEntregaDias());
            CorpoValidado validado = validarCorpo(existente.getProductId(),
                    body.mediaOrigem() != null ? parseOrigem(body.mediaOrigem()) : existente.getMediaOrigem(),
                    body.mediaMensal() != null ? body.mediaMensal() : existente.getMediaMensal(),
                    body.periodicidade() != null ? parsePeriodicidade(body.periodicidade()) : existente.getPeriodicidade(),
                    quantidade, limite, stockMinimo, stockSeguranca, prazo);
            existente.setMediaOrigem(validado.mediaOrigem());
            existente.setMediaMensal(validado.mediaMensal());
            existente.setPeriodicidade(validado.periodicidade());
            existente.setQuantidade(validado.quantidade());
            existente.setLimiteMaximoUsd(validado.limiteMaximoUsd());
            existente.setStockMinimo(validado.stockMinimo());
            existente.setStockSeguranca(validado.stockSeguranca());
            existente.setPrazoEntregaDias(validado.prazoEntregaDias());
            data.put("mediaOrigem", validado.mediaOrigem().name());
            data.put("mediaMensal", validado.mediaMensal());
            data.put("periodicidade", validado.periodicidade().name());
            data.put("quantidade", validado.quantidade());
            data.put("limiteMaximoUsd", validado.limiteMaximoUsd());
            data.put("stockMinimo", validado.stockMinimo());
            data.put("stockSeguranca", validado.stockSeguranca());
            data.put("prazoEntregaDias", validado.prazoEntregaDias());
        }

        String produto = nomeDoProduto(existente);
        data.put("produto", produto);
        auditService.recordSafe(new AuditService.Entry(actor, PoRoboAuditoria.REGRA_ATUALIZADA, PoRoboAuditoria.ENTIDADE_REGRA,
                existente.getId(), produto, data));
        return PoRoboRegraDto.from(existente, PoRoboRegraDto.ProductRef.de(productRepository.findById(existente.getProductId()).orElse(null)));
    }

    @Transactional
    public void remover(String companyId, String id, Actor actor) {
        PoRoboRegra existente = regraRepository.findById(id).orElse(null);
        if (existente == null || !existente.getCompanyId().equals(companyId)) throw new NotFoundException("Regra");
        String produto = nomeDoProduto(existente);
        regraRepository.delete(existente); // as propostas e previsões ficam no histórico (regra_id passa a NULL)
        auditService.recordSafe(new AuditService.Entry(actor, PoRoboAuditoria.REGRA_REMOVIDA, PoRoboAuditoria.ENTIDADE_REGRA,
                existente.getId(), produto, mapa("produto", produto)));
    }
}
