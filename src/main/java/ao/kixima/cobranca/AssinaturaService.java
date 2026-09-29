package ao.kixima.cobranca;

import ao.kixima.apikey.ApiKeyRepository;
import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.catalog.UploadFilters;
import ao.kixima.cobranca.CobrancaDtos.PlanoCobrancaDto;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.ForbiddenException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.common.reference.ReferenceCounterService;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyPlan;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.CompanyStatus;
import ao.kixima.contract.ContractRepository;
import ao.kixima.contract.ContractStatus;
import ao.kixima.invite.EmployeeInviteRepository;
import ao.kixima.invite.InviteStatus;
import ao.kixima.notification.NotificationChannel;
import ao.kixima.notification.NotificationService;
import ao.kixima.notification.NotificationType;
import ao.kixima.plan.PlanFeatureFlag;
import ao.kixima.plan.PlanLimit;
import ao.kixima.plan.PlanService;
import ao.kixima.plan.SubscriptionState;
import ao.kixima.security.PersonaRole;
import ao.kixima.storage.StorageService;
import ao.kixima.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Espelha assinaturaService.js — pedir um plano, pagar (por transferência ou
 * por um canal automático), o plano ativa-se. As duas regras: (1) O PLANO SÓ
 * MUDA NA CONFIRMAÇÃO; (2) O PREÇO CONGELA NO PEDIDO. Os avisos escalonados de
 * expiração vivem em {@link ao.kixima.plan.SubscriptionExpiryService}.
 */
@Service
public class AssinaturaService {

    /** Estados em que uma cobrança ainda está viva — só pode haver uma de cada vez por empresa. */
    public static final List<CobrancaStatus> EM_ABERTO = List.of(CobrancaStatus.PENDENTE, CobrancaStatus.COMPROVATIVO_ENVIADO);
    /** Só BASE e CORE — o PRO, de maior valor, fica exclusivamente na transferência manual confirmada por um humano. */
    public static final List<CompanyPlan> PLANOS_COM_GATEWAY = List.of(CompanyPlan.BASE, CompanyPlan.CORE);
    private static final long DIA_MS = 24L * 60 * 60 * 1000;

    /** Funcionalidades que deixam RASTO: quem desce de plano perde-as, e cada entrada sabe contar o que existe. */
    private record FuncionalidadeComUso(PlanFeatureFlag feature, String label, Function<String, Long> contar, String consequencia) {
    }

    private final List<FuncionalidadeComUso> funcionalidadesComUso;

    public record Impedimento(String codigo, String dimensao, String minimo, String plano, Integer lugares, Integer ocupados) {
        static Impedimento dimensao(String dimensao, String minimo) {
            return new Impedimento("DIMENSAO_EXIGE_PLANO", dimensao, minimo, null, null, null);
        }

        static Impedimento lugares(String plano, int lugares, int ocupados) {
            return new Impedimento("LUGARES_INSUFICIENTES", null, null, plano, lugares, ocupados);
        }
    }

    public record Perda(String label, long quantidade, String consequencia) {
    }

    public record DadosBancarios(String titular, String banco, String iban, String swift, String moeda, boolean configurado) {
    }

    private final PlanoCobrancaRepository cobrancaRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final EmployeeInviteRepository inviteRepository;
    private final PlanService planService;
    private final ReferenceCounterService referenceCounterService;
    private final StorageService storageService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final CanaisPagamentoService canaisPagamentoService;
    private final DadosBancarios dadosBancarios;

    public AssinaturaService(PlanoCobrancaRepository cobrancaRepository, CompanyRepository companyRepository, UserRepository userRepository,
                             EmployeeInviteRepository inviteRepository, PlanService planService, ReferenceCounterService referenceCounterService,
                             StorageService storageService, AuditService auditService, NotificationService notificationService,
                             CanaisPagamentoService canaisPagamentoService, ApiKeyRepository apiKeyRepository, ContractRepository contractRepository,
                             JdbcTemplate jdbcTemplate,
                             @Value("${kixima.banco.titular:}") String bancoTitular, @Value("${kixima.banco.nome:}") String bancoNome,
                             @Value("${kixima.banco.iban:}") String bancoIban, @Value("${kixima.banco.swift:}") String bancoSwift,
                             @Value("${kixima.banco.moeda:USD}") String bancoMoeda) {
        this.cobrancaRepository = cobrancaRepository;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.inviteRepository = inviteRepository;
        this.planService = planService;
        this.referenceCounterService = referenceCounterService;
        this.storageService = storageService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.canaisPagamentoService = canaisPagamentoService;
        this.dadosBancarios = dadosBancarios(bancoTitular, bancoNome, bancoIban, bancoSwift, bancoMoeda);
        this.funcionalidadesComUso = List.of(
                new FuncionalidadeComUso(PlanFeatureFlag.API_CATALOGO, "chaves de API do catálogo ativas",
                        apiKeyRepository::countByCompanyIdAndRevogadaEmIsNull,
                        "as chaves deixam de autenticar e qualquer integração que as use pára"),
                // `supplier_id` e não `company_id`: os kits são do fornecedor que os publica. Contado por SQL — o
                // domínio Kit (grupo D) ainda não tem entidade Java.
                new FuncionalidadeComUso(PlanFeatureFlag.KITS, "kits publicados",
                        companyId -> jdbcTemplate.queryForObject("SELECT count(*) FROM kits WHERE supplier_id = ?", Long.class, companyId),
                        "os kits deixam de estar visíveis no marketplace"),
                new FuncionalidadeComUso(PlanFeatureFlag.FRAMEWORK_CONTRACTS, "contratos-quadro",
                        companyId -> contractRepository.countDaEmpresaComStatus(companyId, ContractStatus.ATIVO),
                        "não poderá criar novos contratos-quadro"));
    }

    /** Os dados bancários da KIXIMA. O IBAN é o único indispensável: sem ele não há transferência possível. */
    static DadosBancarios dadosBancarios(String titular, String banco, String iban, String swift, String moeda) {
        String i = limpar(iban);
        return new DadosBancarios(limpar(titular), limpar(banco), i, limpar(swift), limpar(moeda) == null ? "USD" : limpar(moeda), i != null);
    }

    private static String limpar(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    public DadosBancarios dadosBancarios() {
        return dadosBancarios;
    }

    // --- Leitura ----------------------------------------------------------------

    static Integer diasAte(Instant data, Instant agora) {
        if (data == null) return null;
        return (int) Math.ceil((data.toEpochMilli() - agora.toEpochMilli()) / (double) DIA_MS);
    }

    /** Lugares ocupados: utilizadores ativos MAIS convites por aceitar — a mesma conta de InviteService.assertLugaresDisponiveis. */
    @Transactional(readOnly = true)
    public int lugaresOcupados(String companyId) {
        long ativos = userRepository.countByCompanyIdAndActiveTrue(companyId);
        long convites = inviteRepository.countByCompanyIdAndStatusAndExpiresAtAfter(companyId, InviteStatus.PENDENTE, Instant.now());
        return (int) (ativos + convites);
    }

    /**
     * Porque é que esta empresa NÃO pode passar para este plano — ou null se
     * pode. Devolve os DADOS do impedimento (código + números), não a frase.
     * RENOVAR o plano atual nunca se bloqueia por lugares.
     */
    Impedimento impedimento(Company company, CompanyPlan planoNovo, int ocupados) {
        if (!planService.planAllowed(company.getSize(), planoNovo)) {
            return Impedimento.dimensao(company.getSize().name(), planService.requiredPlan(company.getSize()).name());
        }
        if (planService.normalizarPlano(company.getPlan()) == planService.normalizarPlano(planoNovo)) return null;
        Integer lugares = planService.limite(planoNovo, PlanLimit.LUGARES_INCLUIDOS);
        if (lugares != null && ocupados > lugares) return Impedimento.lugares(planoNovo.name(), lugares, ocupados);
        return null;
    }

    /** A mesma coisa em português — sai do MESMO objeto que a interface recebe. */
    public static String impedimentoEmTexto(Impedimento imp) {
        if (imp == null) return null;
        if ("DIMENSAO_EXIGE_PLANO".equals(imp.codigo())) {
            return "Empresas de dimensão " + imp.dimensao() + " têm de subscrever o plano " + imp.minimo() + ".";
        }
        return "O plano " + imp.plano() + " inclui " + imp.lugares() + " lugares e a empresa tem " + imp.ocupados()
                + " (utilizadores ativos mais convites por aceitar). Desative os utilizadores em excesso antes de descer de plano.";
    }

    /** O que esta empresa PERDE ao descer para este plano, com números reais. */
    List<Perda> perdas(Company company, CompanyPlan planoNovo) {
        List<Perda> out = new ArrayList<>();
        for (FuncionalidadeComUso f : funcionalidadesComUso) {
            if (planService.hasFeature(company.getPlan(), f.feature()) && !planService.hasFeature(planoNovo, f.feature())) {
                long n = f.contar().apply(company.getId());
                if (n > 0) out.add(new Perda(f.label(), n, f.consequencia()));
            }
        }
        return out;
    }

    private static String direcao(List<CompanyPlan> escada, CompanyPlan atual, CompanyPlan alvo) {
        int a = escada.indexOf(atual), b = escada.indexOf(alvo);
        return b > a ? "SUBIR" : b < a ? "DESCER" : "RENOVAR";
    }

    private PlanoCobranca emAbertoDe(String companyId) {
        List<PlanoCobranca> l = cobrancaRepository.findByCompanyIdAndStatusInOrderByCreatedAtDesc(companyId, EM_ABERTO, PageRequest.of(0, 1));
        return l.isEmpty() ? null : l.get(0);
    }

    /** Estado da subscrição para a página da empresa: plano, validade, cobrança em aberto e a escada com o que impede cada degrau. */
    @Transactional(readOnly = true)
    public Map<String, Object> estado(String companyId) {
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        int ocupados = lugaresOcupados(companyId);
        PlanoCobranca emAberto = emAbertoDe(companyId);
        List<PlanoCobranca> historico = cobrancaRepository.findByCompanyIdAndStatusNotInOrderByCreatedAtDesc(companyId, EM_ABERTO, PageRequest.of(0, 20));
        CompanyPlan atual = planService.normalizarPlano(company.getPlan());
        Instant agora = Instant.now();

        List<Map<String, Object>> opcoes = new ArrayList<>();
        for (CompanyPlan plano : planService.escada()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("plano", plano.name());
            o.put("preco", planService.preco(plano));
            o.put("features", planService.features(plano));
            o.put("atual", plano == atual);
            o.put("direcao", direcao(planService.escada(), atual, plano));
            o.put("impedimento", impedimento(company, plano, ocupados));
            o.put("perdas", perdas(company, plano));
            opcoes.add(o);
        }

        Integer dias = diasAte(company.getPlanoValidoAte(), agora);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> empresa = new LinkedHashMap<>();
        empresa.put("id", company.getId());
        empresa.put("name", company.getName());
        empresa.put("size", company.getSize().name());
        out.put("empresa", empresa);
        out.put("banco", dadosBancarios);
        out.put("planoAtual", atual.name());
        out.put("validoAte", company.getPlanoValidoAte());
        out.put("diasAteExpirar", dias);
        // Uma subscrição vencida NÃO desce o plano sozinha — fica visível aqui e na fila do Admin do Sistema.
        out.put("expirada", company.getPlanoValidoAte() != null && company.getPlanoValidoAte().isBefore(agora));
        out.put("estadoSubscricao", planService.estadoSubscricao(company, agora).name());
        Integer graceRestantes = null;
        if (company.getPlanoValidoAte() != null) {
            int restantes = planService.gracePeriodDays() - (-dias);
            graceRestantes = Math.max(restantes, 0);
        }
        out.put("graceDiasRestantes", graceRestantes);
        out.put("lugaresOcupados", ocupados);
        out.put("lugaresIncluidos", planService.limite(atual, PlanLimit.LUGARES_INCLUIDOS));
        out.put("emAberto", emAberto == null ? null : PlanoCobrancaDto.de(emAberto, false));
        out.put("historico", historico.stream().map(c -> PlanoCobrancaDto.de(c, false)).toList());
        out.put("opcoes", opcoes);
        return out;
    }

    // --- Pedir ------------------------------------------------------------------

    private ConflictException jaExisteAberta(PlanoCobranca aberta) {
        return new ConflictException("Já existe a cobrança " + aberta.getReferencia() + " por liquidar (" + aberta.getPlanoNovo()
                + ", " + aberta.getValorUsd().stripTrailingZeros().toPlainString() + " USD). Conclua ou cancele essa antes de pedir outra.");
    }

    /** Emite a cobrança de um plano. Não muda o plano — emite a conta. */
    @Transactional
    public PlanoCobrancaDto pedir(String companyId, String planoBruto, String userId, boolean aceitaPerdas, Actor actor) {
        String upper = planoBruto == null ? "" : planoBruto.toUpperCase();
        CompanyPlan plano;
        try {
            plano = CompanyPlan.valueOf(upper);
            if (!planService.escada().contains(plano)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Plano desconhecido: \"" + planoBruto + "\". Os planos são "
                    + String.join(", ", planService.escada().stream().map(Enum::name).toList()) + ".");
        }
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        if (company.getStatus() != CompanyStatus.APROVADA) {
            throw new BusinessRuleException("A empresa tem de estar aprovada para subscrever um plano.");
        }
        PlanoCobranca aberta = emAbertoDe(companyId);
        if (aberta != null) throw jaExisteAberta(aberta);

        int ocupados = lugaresOcupados(companyId);
        Impedimento bloqueio = impedimento(company, plano, ocupados);
        if (bloqueio != null) throw new BusinessRuleException(impedimentoEmTexto(bloqueio));

        List<Perda> aPerder = perdas(company, plano);
        if (!aPerder.isEmpty() && !aceitaPerdas) {
            String lista = String.join("; ", aPerder.stream().map(p -> p.quantidade() + " " + p.label()).toList());
            throw new BusinessRuleException("Descer para o plano " + plano + " faz perder: " + lista + ". Confirme que aceita perder isto para continuar.");
        }

        PlanService.Preco preco = planService.preco(plano);
        String referencia = referenceCounterService.nextReference("SUB", "planoCobranca");
        PlanoCobranca cobranca = new PlanoCobranca(UUID.randomUUID().toString(), referencia, companyId, company.getPlan(), plano,
                preco.valorUsd(), preco.periodo(), preco.meses(), userId, Instant.now());
        try {
            cobrancaRepository.saveAndFlush(cobranca);
        } catch (DataIntegrityViolationException e) {
            // O índice único parcial (migração 20260918000000) é a garantia real contra dois pedidos concorrentes.
            PlanoCobranca aberta2 = emAbertoDe(companyId);
            if (aberta2 != null) throw jaExisteAberta(aberta2);
            throw e;
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("de", company.getPlan().name());
        detail.put("para", plano.name());
        detail.put("valorUsd", preco.valorUsd().toPlainString());
        detail.put("periodo", preco.periodo());
        if (!aPerder.isEmpty()) detail.put("perdasAceites", aPerder.stream().map(p -> p.quantidade() + " " + p.label()).toList());
        auditService.recordSafe(new AuditService.Entry(actor, "SUBSCRICAO_PEDIDA", "PlanoCobranca", cobranca.getId(), referencia, detail));
        return PlanoCobrancaDto.de(cobranca, false);
    }

    // --- Pagar ------------------------------------------------------------------

    private static void validarComprovativo(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Anexe o comprovativo da transferência (PDF ou imagem) para submeter o pagamento.");
        }
        String tipo = file.getContentType();
        boolean valido = tipo != null && (tipo.matches("^image/(png|jpe?g|webp|gif)$") || tipo.equals("application/pdf"));
        if (!valido) throw new ValidationException("Documento inválido — use PDF ou imagem (PNG/JPG).");
        // `uploadDocuments` (config/upload.js): o multer aplica o fileFilter primeiro e só depois o limite de 10MB.
        UploadFilters.tamanho(file, UploadFilters.LIMITE_DOCUMENTO);
    }

    static byte[] bytesDe(MultipartFile f) {
        try {
            return f.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Falha a ler o comprovativo enviado.", e);
        }
    }

    /** Carrega o comprovativo da transferência. Obrigatório: sem ele "paguei" é só uma palavra. */
    @Transactional
    public PlanoCobrancaDto submeterComprovativo(String companyId, String cobrancaId, MultipartFile file, Actor actor) {
        validarComprovativo(file);
        PlanoCobranca cobranca = cobrancaRepository.findById(cobrancaId).orElseThrow(() -> new NotFoundException("Cobrança"));
        if (!cobranca.getCompanyId().equals(companyId)) throw new ForbiddenException("Só pode pagar cobranças da sua própria empresa.");
        if (!cobranca.emAberto()) {
            throw new ConflictException("A cobrança " + cobranca.getReferencia() + " está " + cobranca.getStatus().name().toLowerCase() + " e não aceita comprovativo.");
        }
        String url = storageService.saveFile(bytesDe(file), file.getOriginalFilename(), file.getContentType(), "subscricao-" + cobranca.getReferencia(), "proofs");
        cobranca.registarComprovativo(url, Instant.now());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("comprovativo", file.getOriginalFilename() == null || file.getOriginalFilename().isBlank() ? "comprovativo" : file.getOriginalFilename());
        detail.put("valorUsd", cobranca.getValorUsd().toPlainString());
        auditService.recordSafe(new AuditService.Entry(actor, "SUBSCRICAO_COMPROVATIVO_ENVIADO", "PlanoCobranca", cobranca.getId(), cobranca.getReferencia(), detail));

        // A KIXIMA tem de saber que há dinheiro à espera de confirmação; falhar o email não desfaz o comprovativo.
        try {
            notificationService.notifyPlatformRole(PersonaRole.ADMIN_SISTEMA, NotificationType.SUBSCRICAO_COMPROVATIVO,
                    "Comprovativo de subscrição recebido",
                    cobranca.getReferencia() + ": comprovativo carregado para o plano " + cobranca.getPlanoNovo() + " ("
                            + cobranca.getValorUsd().stripTrailingZeros().toPlainString() + " USD). Aguarda confirmação.",
                    "PlanoCobranca", cobranca.getId());
        } catch (RuntimeException ignorado) {
            // .catch(() => {}) no Node
        }
        return PlanoCobrancaDto.de(cobranca, false);
    }

    // --- Confirmar --------------------------------------------------------------

    /**
     * Até quando a subscrição fica paga: conta a partir do fim da atual quando
     * ainda está em vigor; se já expirou, conta a partir de hoje.
     */
    public static Instant novoValidoAte(Instant validoAtual, int meses, Instant agora) {
        Instant base = validoAtual != null && validoAtual.isAfter(agora) ? validoAtual : agora;
        return ZonedDateTime.ofInstant(base, ZoneOffset.UTC).plusMonths(meses).toInstant();
    }

    /** O único sítio em toda a plataforma onde uma subscrição paga muda o plano — partilhado pelos dois caminhos. */
    private PlanoCobranca aplicarConfirmacao(PlanoCobranca cobranca, String confirmadaPor, String notas, Actor actor, String mensagemNotificacao) {
        Company company = companyRepository.findById(cobranca.getCompanyId()).orElseThrow(() -> new NotFoundException("Empresa"));
        Instant agora = Instant.now();
        Instant validoAte = novoValidoAte(company.getPlanoValidoAte(), cobranca.getMeses(), agora);

        cobranca.confirmar(agora, validoAte, confirmadaPor, notas);
        company.setPlan(cobranca.getPlanoNovo());
        // Derivado do plano: sem isto, quem pagou o Pro continuava no fundo da pesquisa.
        company.setSearchRank(planService.rankDoPlano(cobranca.getPlanoNovo()));
        company.setPlanoValidoAte(validoAte);
        // Novo ciclo de pagamento: os avisos já enviados eram sobre o prazo ANTERIOR.
        company.setUltimoAvisoSubscricaoTier(null);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("empresa", company.getName());
        detail.put("de", cobranca.getPlanoAtual().name());
        detail.put("para", cobranca.getPlanoNovo().name());
        detail.put("valorUsd", cobranca.getValorUsd().toPlainString());
        detail.put("canal", cobranca.getCanal().name());
        detail.put("validoAte", validoAte.toString());
        // Auditoria DENTRO da transação: um plano que muda sem registo não se consegue explicar a ninguém depois.
        auditService.record(new AuditService.Entry(actor, "SUBSCRICAO_CONFIRMADA", "PlanoCobranca", cobranca.getId(), cobranca.getReferencia(), detail));

        try {
            notificationService.notifyUsersByRole(cobranca.getCompanyId(), List.of(PersonaRole.COMPANY_ADMIN, PersonaRole.FINANCEIRO),
                    NotificationType.SUBSCRICAO_CONFIRMADA, "Plano " + cobranca.getPlanoNovo() + " ativo",
                    mensagemNotificacao != null ? mensagemNotificacao
                            : "A subscrição " + cobranca.getReferencia() + " foi confirmada. O plano " + cobranca.getPlanoNovo()
                            + " está ativo até " + validoAte.toString().substring(0, 10) + ".",
                    NotificationChannel.IN_APP_EMAIL, "PlanoCobranca", cobranca.getId());
        } catch (RuntimeException ignorado) {
            // .catch(() => {}) no Node
        }
        return cobranca;
    }

    @Transactional
    public PlanoCobrancaDto confirmar(String cobrancaId, String adminId, String notas, Actor actor) {
        PlanoCobranca cobranca = cobrancaRepository.findById(cobrancaId).orElseThrow(() -> new NotFoundException("Cobrança"));
        if (cobranca.getStatus() == CobrancaStatus.CONFIRMADA) throw new ConflictException("A cobrança " + cobranca.getReferencia() + " já foi confirmada.");
        if (cobranca.getStatus() == CobrancaStatus.CANCELADA) throw new ConflictException("A cobrança " + cobranca.getReferencia() + " está cancelada.");
        if (cobranca.getComprovativoUrl() == null) {
            throw new BusinessRuleException("A cobrança " + cobranca.getReferencia() + " não tem comprovativo. "
                    + "Confirmar sem ele deixaria a plataforma a afirmar um pagamento que ninguém consegue mostrar.");
        }
        return PlanoCobrancaDto.de(aplicarConfirmacao(cobranca, adminId, notas == null || notas.isBlank() ? null : notas,
                actor != null ? actor : new Actor(adminId, null, null, null, null), null), false);
    }

    // --- Pagamento automático (EMIS, PayPay, bancos) -----------------------------

    /** Inicia o pagamento num canal automático e guarda a referência externa. NÃO confirma nada — só o webhook o faz. */
    @Transactional
    public PlanoCobrancaDto iniciarPagamentoGateway(String companyId, String cobrancaId, String canalBruto, String telemovel, Actor actor) {
        CanalCobranca canal = null;
        try {
            canal = canalBruto == null ? null : CanalCobranca.valueOf(canalBruto.toUpperCase());
        } catch (IllegalArgumentException ignorado) {
            // fica null → desconhecido
        }
        if (canal == null || !CanaisPagamentoService.CANAIS_GATEWAY.contains(canal)) {
            throw new ValidationException("Canal desconhecido: \"" + canalBruto + "\". Os canais automáticos são: "
                    + String.join(", ", CanaisPagamentoService.CANAIS_GATEWAY.stream().map(Enum::name).toList()) + ".");
        }
        PlanoCobranca cobranca = cobrancaRepository.findById(cobrancaId).orElseThrow(() -> new NotFoundException("Cobrança"));
        if (!cobranca.getCompanyId().equals(companyId)) throw new ForbiddenException("Só pode pagar cobranças da sua própria empresa.");
        if (cobranca.getStatus() != CobrancaStatus.PENDENTE) {
            throw new ConflictException("A cobrança " + cobranca.getReferencia() + " está " + cobranca.getStatus().name().toLowerCase() + " e não aceita um novo pagamento.");
        }
        if (!PLANOS_COM_GATEWAY.contains(cobranca.getPlanoNovo())) {
            throw new BusinessRuleException("O plano " + cobranca.getPlanoNovo() + " só se paga por transferência bancária. Os canais automáticos existem apenas para BASE/CORE.");
        }
        GatewayAdapter adaptador = canaisPagamentoService.adaptador(canal);
        Map<String, Object> resultado = adaptador.pedirPagamento(new GatewayAdapter.PedidoPagamento(cobranca.getReferencia(), cobranca.getValorUsd(), "USD", telemovel));
        Object id = resultado.get("id") != null ? resultado.get("id") : resultado.get("transactionId");
        String referenciaExterna = id == null ? "" : String.valueOf(id);
        if (referenciaExterna.isBlank()) {
            throw new IllegalStateException(canal + " não devolveu um identificador de transação — não há forma de confirmar este pagamento depois.");
        }
        cobranca.iniciarGateway(canal, referenciaExterna, telemovel == null || telemovel.isBlank() ? null : telemovel);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("canal", canal.name());
        detail.put("referenciaExterna", referenciaExterna);
        auditService.recordSafe(new AuditService.Entry(actor, "SUBSCRICAO_PAGAMENTO_INICIADO", "PlanoCobranca", cobranca.getId(), cobranca.getReferencia(), detail));
        return PlanoCobrancaDto.de(cobranca, false);
    }

    /** O gateway confirmou — chamado só pela rota de webhook. Idempotente: um callback duplicado não confirma duas vezes. */
    @Transactional
    public PlanoCobrancaDto confirmarViaGateway(String cobrancaId, CanalCobranca canal, String referenciaExterna) {
        PlanoCobranca cobranca = cobrancaRepository.findById(cobrancaId).orElseThrow(() -> new NotFoundException("Cobrança"));
        if (cobranca.getStatus() == CobrancaStatus.CONFIRMADA) return PlanoCobrancaDto.de(cobranca, false);
        if (cobranca.getStatus() == CobrancaStatus.CANCELADA) {
            throw new ConflictException("A cobrança " + cobranca.getReferencia() + " está cancelada — o pagamento chegou tarde de mais.");
        }
        if (cobranca.getCanal() != canal || referenciaExterna == null || !referenciaExterna.equals(cobranca.getReferenciaExterna())) {
            throw new ConflictException("O callback de " + canal + " (" + referenciaExterna + ") não corresponde ao pagamento iniciado para " + cobranca.getReferencia() + ".");
        }
        // Sem adminId — ninguém da KIXIMA carregou em nada. `actorName` diz a verdade em vez de inventar um utilizador "sistema".
        Actor actor = new Actor(null, "Pagamento automático (" + canal + ")", null, cobranca.getCompanyId(), null);
        return PlanoCobrancaDto.de(aplicarConfirmacao(cobranca, null, null, actor,
                "A subscrição " + cobranca.getReferencia() + " foi paga via " + canal + " e confirmada automaticamente. O plano "
                        + cobranca.getPlanoNovo() + " está ativo."), false);
    }

    /** Cancela uma cobrança em aberto. O motivo é obrigatório. {@code companyId} null = Admin do Sistema (qualquer uma). */
    @Transactional
    public PlanoCobrancaDto cancelar(String cobrancaId, String motivo, String companyId, Actor actor) {
        if (motivo == null || motivo.isBlank()) throw new ValidationException("Indique o motivo do cancelamento.");
        PlanoCobranca cobranca = cobrancaRepository.findById(cobrancaId).orElseThrow(() -> new NotFoundException("Cobrança"));
        if (companyId != null && !cobranca.getCompanyId().equals(companyId)) throw new ForbiddenException("Só pode cancelar cobranças da sua própria empresa.");
        if (!cobranca.emAberto()) throw new ConflictException("A cobrança " + cobranca.getReferencia() + " está " + cobranca.getStatus().name().toLowerCase() + ".");
        cobranca.cancelar(motivo.trim());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("motivo", motivo.trim());
        detail.put("plano", cobranca.getPlanoNovo().name());
        detail.put("valorUsd", cobranca.getValorUsd().toPlainString());
        auditService.recordSafe(new AuditService.Entry(actor, "SUBSCRICAO_CANCELADA", "PlanoCobranca", cobranca.getId(), cobranca.getReferencia(), detail));
        return PlanoCobrancaDto.de(cobranca, false);
    }

    // --- Admin do Sistema -------------------------------------------------------

    /** A fila de trabalho da KIXIMA: cobranças em aberto e subscrições vencidas (GRACE e RESTRITA separadas). */
    @Transactional(readOnly = true)
    public Map<String, Object> fila() {
        Instant agora = Instant.now();
        List<PlanoCobranca> abertas = cobrancaRepository.findByStatusInOrderByStatusDescCreatedAtAsc(EM_ABERTO);
        Map<String, Company> empresas = companyRepository.findAllById(abertas.stream().map(PlanoCobranca::getCompanyId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Company::getId, c -> c));
        List<PlanoCobrancaDto> emAberto = abertas.stream().map(c -> PlanoCobrancaDto.de(c, empresas.get(c.getCompanyId()))).toList();
        List<Map<String, Object>> vencidas = new ArrayList<>();
        for (Company c : companyRepository.findByPlanoValidoAteIsNotNull()) {
            if (!c.getPlanoValidoAte().isBefore(agora)) continue;
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", c.getId());
            v.put("name", c.getName());
            v.put("plan", c.getPlan() == null ? null : c.getPlan().name());
            v.put("planoValidoAte", c.getPlanoValidoAte());
            v.put("diasVencida", -diasAte(c.getPlanoValidoAte(), agora));
            v.put("estadoSubscricao", planService.estadoSubscricao(c, agora).name());
            vencidas.add(v);
        }
        vencidas.sort(java.util.Comparator.comparing(v -> (Instant) v.get("planoValidoAte")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("emAberto", emAberto);
        out.put("vencidas", vencidas);
        out.put("emGrace", vencidas.stream().filter(v -> SubscriptionState.GRACE.name().equals(v.get("estadoSubscricao"))).toList());
        out.put("restritas", vencidas.stream().filter(v -> SubscriptionState.RESTRITA.name().equals(v.get("estadoSubscricao"))).toList());
        out.put("porConfirmar", emAberto.stream().filter(c -> "COMPROVATIVO_ENVIADO".equals(c.status())).count());
        out.put("porPagar", emAberto.stream().filter(c -> "PENDENTE".equals(c.status())).count());
        return out;
    }
}
