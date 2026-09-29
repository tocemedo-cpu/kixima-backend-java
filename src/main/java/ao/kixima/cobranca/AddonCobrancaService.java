package ao.kixima.cobranca;

import ao.kixima.common.Decimais;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import ao.kixima.addon.AddonService;
import ao.kixima.addon.CompanyAddon;
import ao.kixima.addon.CompanyAddonRepository;
import ao.kixima.addon.CompanyAddonStatus;
import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.catalog.UploadFilters;
import ao.kixima.cobranca.CobrancaDtos.AddonCobrancaDto;
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
import ao.kixima.notification.NotificationChannel;
import ao.kixima.notification.NotificationService;
import ao.kixima.notification.NotificationType;
import ao.kixima.plan.PlanService;
import ao.kixima.security.PersonaRole;
import ao.kixima.storage.StorageService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha o fluxo de cobrança de addonService.js (pedir → comprovativo →
 * confirmar por um humano da KIXIMA → add-on ATIVO), sobre a guarda já
 * portada em {@link AddonService}. As mesmas duas regras de assinaturaService:
 * o add-on só ativa na confirmação; o preço congela no pedido.
 */
@Service
public class AddonCobrancaService {

    static final List<CobrancaStatus> EM_ABERTO = AssinaturaService.EM_ABERTO;

    public record Preco(@JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal valorUsd, String periodo, int meses, @JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal porMesUsd) {
    }

    private final AddonService addonService;
    private final AddonCobrancaRepository cobrancaRepository;
    private final CompanyAddonRepository companyAddonRepository;
    private final CompanyRepository companyRepository;
    private final PlanService planService;
    private final ReferenceCounterService referenceCounterService;
    private final StorageService storageService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public AddonCobrancaService(AddonService addonService, AddonCobrancaRepository cobrancaRepository, CompanyAddonRepository companyAddonRepository,
                                CompanyRepository companyRepository, PlanService planService, ReferenceCounterService referenceCounterService,
                                StorageService storageService, AuditService auditService, NotificationService notificationService) {
        this.addonService = addonService;
        this.cobrancaRepository = cobrancaRepository;
        this.companyAddonRepository = companyAddonRepository;
        this.companyRepository = companyRepository;
        this.planService = planService;
        this.referenceCounterService = referenceCounterService;
        this.storageService = storageService;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    public Preco precoDe(String addonKey) {
        AddonService.Definicao def = addonService.definicao(addonKey);
        int meses = PlanService.MESES_DO_PERIODO.getOrDefault(def.periodo(), 1);
        return new Preco(def.valorUsd(), def.periodo(), meses, def.valorUsd().divide(BigDecimal.valueOf(meses), 2, RoundingMode.HALF_UP));
    }

    private boolean planSuficiente(CompanyPlan plano, String minimo) {
        List<CompanyPlan> escada = planService.escada();
        return escada.indexOf(planService.normalizarPlano(plano)) >= escada.indexOf(CompanyPlan.valueOf(minimo));
    }

    /** Catálogo para a interface (preço + se exige upgrade de plano primeiro). */
    public List<Map<String, Object>> catalogo() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : addonService.chaves()) {
            AddonService.Definicao def = addonService.definicao(key);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("addonKey", key);
            m.put("label", def.label());
            m.put("requerPlano", def.requerPlano());
            m.put("preco", precoDe(key));
            out.add(m);
        }
        return out;
    }

    private AddonCobranca emAbertoDe(String companyId, String addonKey) {
        List<AddonCobranca> l = cobrancaRepository.findByCompanyIdAndAddonKeyAndStatusInOrderByCreatedAtDesc(companyId, addonKey, EM_ABERTO, PageRequest.of(0, 1));
        return l.isEmpty() ? null : l.get(0);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> estado(String companyId, String addonKey) {
        AddonService.Definicao def = addonService.definicao(addonKey);
        CompanyAddon addon = companyAddonRepository.findByCompanyIdAndAddonKey(companyId, addonKey).orElse(null);
        AddonCobranca emAberto = emAbertoDe(companyId, addonKey);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("addonKey", addonKey);
        m.put("label", def.label());
        m.put("preco", precoDe(addonKey));
        m.put("ativo", addonService.aindaValido(addon, Instant.now()));
        m.put("activatedAt", addon == null ? null : addon.getActivatedAt());
        m.put("validoAte", addon == null ? null : addon.getValidoAte());
        m.put("emAberto", emAberto == null ? null : AddonCobrancaDto.de(emAberto, false));
        return m;
    }

    private ConflictException jaExisteAberta(AddonCobranca aberta, AddonService.Definicao def) {
        return new ConflictException("Já existe a cobrança " + aberta.getReferencia() + " do add-on \"" + def.label() + "\" por liquidar ("
                + aberta.getValorUsd().stripTrailingZeros().toPlainString() + " USD). Conclua ou cancele essa antes de pedir outra.");
    }

    @Transactional
    public AddonCobrancaDto pedir(String companyId, String addonKey, String userId, Actor actor) {
        AddonService.Definicao def = addonService.definicao(addonKey);
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        if (company.getStatus() != CompanyStatus.APROVADA) throw new BusinessRuleException("A empresa tem de estar aprovada para pedir um add-on.");
        if (def.requerPlano() != null && !planSuficiente(company.getPlan(), def.requerPlano())) {
            throw new BusinessRuleException("\"" + def.label() + "\" exige o plano " + def.requerPlano() + " ou superior.");
        }
        // aindaValido, não só status ATIVO: um add-on VENCIDO tem de poder ser renovado.
        CompanyAddon jaAtivo = companyAddonRepository.findByCompanyIdAndAddonKey(companyId, addonKey).orElse(null);
        if (addonService.aindaValido(jaAtivo, Instant.now())) {
            throw new ConflictException("O add-on \"" + def.label() + "\" já está ativo para esta empresa.");
        }
        AddonCobranca aberta = emAbertoDe(companyId, addonKey);
        if (aberta != null) throw jaExisteAberta(aberta, def);

        Preco preco = precoDe(addonKey);
        String referencia = referenceCounterService.nextReference("ADD", "addonCobranca");
        AddonCobranca cobranca = new AddonCobranca(UUID.randomUUID().toString(), referencia, companyId, addonKey, preco.valorUsd(),
                preco.periodo(), preco.meses(), userId, Instant.now());
        try {
            cobrancaRepository.saveAndFlush(cobranca);
        } catch (DataIntegrityViolationException e) {
            AddonCobranca aberta2 = emAbertoDe(companyId, addonKey);
            if (aberta2 != null) throw jaExisteAberta(aberta2, def);
            throw e;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("addonKey", addonKey);
        detail.put("label", def.label());
        detail.put("valorUsd", preco.valorUsd().toPlainString());
        detail.put("periodo", preco.periodo());
        auditService.recordSafe(new AuditService.Entry(actor, "ADDON_PEDIDO", "AddonCobranca", cobranca.getId(), referencia, detail));
        return AddonCobrancaDto.de(cobranca, false);
    }

    @Transactional
    public AddonCobrancaDto submeterComprovativo(String companyId, String cobrancaId, MultipartFile file, Actor actor) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Anexe o comprovativo da transferência (PDF ou imagem) para submeter o pagamento.");
        }
        String tipo = file.getContentType();
        boolean valido = tipo != null && (tipo.matches("^image/(png|jpe?g|webp|gif)$") || tipo.equals("application/pdf"));
        if (!valido) throw new ValidationException("Documento inválido — use PDF ou imagem (PNG/JPG).");
        // `uploadDocuments` (config/upload.js): o multer aplica o fileFilter primeiro e só depois o limite de 10MB.
        UploadFilters.tamanho(file, UploadFilters.LIMITE_DOCUMENTO);

        AddonCobranca cobranca = cobrancaRepository.findById(cobrancaId).orElseThrow(() -> new NotFoundException("Cobrança"));
        if (!cobranca.getCompanyId().equals(companyId)) throw new ForbiddenException("Só pode pagar cobranças da sua própria empresa.");
        if (!cobranca.emAberto()) {
            throw new ConflictException("A cobrança " + cobranca.getReferencia() + " está " + cobranca.getStatus().name().toLowerCase() + " e não aceita comprovativo.");
        }
        String url = storageService.saveFile(AssinaturaService.bytesDe(file), file.getOriginalFilename(), tipo, "addon-" + cobranca.getReferencia(), "proofs");
        cobranca.registarComprovativo(url, Instant.now());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("addonKey", cobranca.getAddonKey());
        detail.put("comprovativo", file.getOriginalFilename() == null || file.getOriginalFilename().isBlank() ? "comprovativo" : file.getOriginalFilename());
        detail.put("valorUsd", cobranca.getValorUsd().toPlainString());
        auditService.recordSafe(new AuditService.Entry(actor, "ADDON_COMPROVATIVO_ENVIADO", "AddonCobranca", cobranca.getId(), cobranca.getReferencia(), detail));

        try {
            String label = addonService.chaves().contains(cobranca.getAddonKey()) ? addonService.definicao(cobranca.getAddonKey()).label() : cobranca.getAddonKey();
            notificationService.notifyPlatformRole(PersonaRole.ADMIN_SISTEMA, NotificationType.SUBSCRICAO_COMPROVATIVO,
                    "Comprovativo de add-on recebido",
                    cobranca.getReferencia() + ": comprovativo carregado para o add-on " + label + " ("
                            + cobranca.getValorUsd().stripTrailingZeros().toPlainString() + " USD). Aguarda confirmação.",
                    "AddonCobranca", cobranca.getId());
        } catch (RuntimeException ignorado) {
            // .catch(() => {}) no Node
        }
        return AddonCobrancaDto.de(cobranca, false);
    }

    /** O único sítio onde um add-on pago se torna ATIVO — a primeira confirmação cria o CompanyAddon, as seguintes renovam. */
    private AddonCobranca aplicarConfirmacao(AddonCobranca cobranca, String confirmadaPor, String notas, Actor actor) {
        AddonService.Definicao def = addonService.definicao(cobranca.getAddonKey());
        Instant agora = Instant.now();
        CompanyAddon existente = companyAddonRepository.findByCompanyIdAndAddonKey(cobranca.getCompanyId(), cobranca.getAddonKey()).orElse(null);
        // A partir da validade JÁ paga (CompanyAddon.validoAte) — um add-on vencido recomeça a contar de hoje.
        Instant validoAte = AssinaturaService.novoValidoAte(
                addonService.aindaValido(existente, agora) ? existente.getValidoAte() : null, cobranca.getMeses(), agora);

        cobranca.confirmar(agora, validoAte, confirmadaPor, notas);
        if (existente == null) {
            CompanyAddon novo = new CompanyAddon(UUID.randomUUID().toString(), cobranca.getCompanyId(), cobranca.getAddonKey());
            novo.ativar(agora, validoAte);
            companyAddonRepository.save(novo);
        } else {
            existente.ativar(existente.getActivatedAt() != null ? existente.getActivatedAt() : agora, validoAte);
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("addonKey", cobranca.getAddonKey());
        detail.put("label", def.label());
        detail.put("valorUsd", cobranca.getValorUsd().toPlainString());
        detail.put("validoAte", validoAte.toString());
        auditService.record(new AuditService.Entry(actor, "ADDON_CONFIRMADO", "AddonCobranca", cobranca.getId(), cobranca.getReferencia(), detail));

        try {
            notificationService.notifyUsersByRole(cobranca.getCompanyId(), List.of(PersonaRole.COMPANY_ADMIN, PersonaRole.FINANCEIRO),
                    NotificationType.SUBSCRICAO_CONFIRMADA, "Add-on \"" + def.label() + "\" ativo",
                    "A cobrança " + cobranca.getReferencia() + " foi confirmada. O add-on \"" + def.label() + "\" está ativo até "
                            + validoAte.toString().substring(0, 10) + ".",
                    NotificationChannel.IN_APP_EMAIL, "AddonCobranca", cobranca.getId());
        } catch (RuntimeException ignorado) {
            // .catch(() => {}) no Node
        }
        return cobranca;
    }

    @Transactional
    public AddonCobrancaDto confirmar(String cobrancaId, String adminId, String notas, Actor actor) {
        AddonCobranca cobranca = cobrancaRepository.findById(cobrancaId).orElseThrow(() -> new NotFoundException("Cobrança"));
        if (cobranca.getStatus() == CobrancaStatus.CONFIRMADA) throw new ConflictException("A cobrança " + cobranca.getReferencia() + " já foi confirmada.");
        if (cobranca.getStatus() == CobrancaStatus.CANCELADA) throw new ConflictException("A cobrança " + cobranca.getReferencia() + " está cancelada.");
        if (cobranca.getComprovativoUrl() == null) {
            throw new BusinessRuleException("A cobrança " + cobranca.getReferencia() + " não tem comprovativo. "
                    + "Confirmar sem ele deixaria a plataforma a afirmar um pagamento que ninguém consegue mostrar.");
        }
        return AddonCobrancaDto.de(aplicarConfirmacao(cobranca, adminId, notas == null || notas.isBlank() ? null : notas,
                actor != null ? actor : new Actor(adminId, null, null, null, null)), false);
    }

    @Transactional
    public AddonCobrancaDto cancelar(String cobrancaId, String motivo, String companyId, Actor actor) {
        if (motivo == null || motivo.isBlank()) throw new ValidationException("Indique o motivo do cancelamento.");
        AddonCobranca cobranca = cobrancaRepository.findById(cobrancaId).orElseThrow(() -> new NotFoundException("Cobrança"));
        if (companyId != null && !cobranca.getCompanyId().equals(companyId)) throw new ForbiddenException("Só pode cancelar cobranças da sua própria empresa.");
        if (!cobranca.emAberto()) throw new ConflictException("A cobrança " + cobranca.getReferencia() + " está " + cobranca.getStatus().name().toLowerCase() + ".");
        cobranca.cancelar(motivo.trim());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("motivo", motivo.trim());
        detail.put("addonKey", cobranca.getAddonKey());
        detail.put("valorUsd", cobranca.getValorUsd().toPlainString());
        auditService.recordSafe(new AuditService.Entry(actor, "ADDON_CANCELADO", "AddonCobranca", cobranca.getId(), cobranca.getReferencia(), detail));
        return AddonCobrancaDto.de(cobranca, false);
    }

    /** A fila de trabalho da KIXIMA: cobranças de add-on em aberto. */
    @Transactional(readOnly = true)
    public Map<String, Object> fila() {
        List<AddonCobranca> abertas = cobrancaRepository.findByStatusInOrderByStatusDescCreatedAtAsc(EM_ABERTO);
        Map<String, Company> empresas = companyRepository.findAllById(abertas.stream().map(AddonCobranca::getCompanyId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Company::getId, c -> c));
        List<AddonCobrancaDto> emAberto = abertas.stream().map(c -> AddonCobrancaDto.de(c, empresas.get(c.getCompanyId()))).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("emAberto", emAberto);
        out.put("porConfirmar", emAberto.stream().filter(c -> "COMPROVATIVO_ENVIADO".equals(c.status())).count());
        out.put("porPagar", emAberto.stream().filter(c -> "PENDENTE".equals(c.status())).count());
        return out;
    }

    static boolean ativoNaBase(CompanyAddon a) {
        return a != null && a.getStatus() == CompanyAddonStatus.ATIVO;
    }
}
