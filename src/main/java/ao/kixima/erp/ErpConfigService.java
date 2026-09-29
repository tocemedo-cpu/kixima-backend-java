package ao.kixima.erp;

import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.CompanyStatus;
import ao.kixima.erp.dto.CompanyRefDto;
import ao.kixima.erp.dto.ErpConfigAuditDto;
import ao.kixima.erp.dto.ErpConfigDto;
import ao.kixima.erp.dto.ErpTestResultDto;
import ao.kixima.erp.dto.LastTestDto;
import ao.kixima.plan.PlanFeatureFlag;
import ao.kixima.plan.PlanService;
import ao.kixima.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/erpConfigService.js — configuração ERP POR
 * EMPRESA, gerida pelo Administrador do Sistema KIXIMA. Guarda a seleção e
 * as credenciais (cifradas, ver {@link ErpCryptoService}) em
 * {@link CompanyErpConfig}, mantém trilho de auditoria própria
 * ({@link CompanyErpConfigAudit}) e sincroniza com o microserviço
 * kixima-integration-service (best-effort, ver {@link ErpIntegrationClient}).
 */
@Service
public class ErpConfigService {

    private static final Logger log = LoggerFactory.getLogger(ErpConfigService.class);

    /** {@link ErpFields#ERP_FIELDS} com as chaves em String — a forma que a resposta JSON usa. */
    private static final Map<String, List<ErpField>> FIELDS_POR_NOME = ErpFields.ERP_FIELDS.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue,
                    (a, b) -> a, LinkedHashMap::new));

    private final CompanyErpConfigRepository configRepository;
    private final CompanyErpConfigAuditRepository auditRepository;
    private final CompanyRepository companyRepository;
    private final ErpCryptoService erpCryptoService;
    private final ErpIntegrationClient integrationClient;
    private final PlanService planService;

    public ErpConfigService(CompanyErpConfigRepository configRepository, CompanyErpConfigAuditRepository auditRepository,
                             CompanyRepository companyRepository, ErpCryptoService erpCryptoService,
                             ErpIntegrationClient integrationClient, PlanService planService) {
        this.configRepository = configRepository;
        this.auditRepository = auditRepository;
        this.companyRepository = companyRepository;
        this.erpCryptoService = erpCryptoService;
        this.integrationClient = integrationClient;
        this.planService = planService;
    }

    @Transactional(readOnly = true)
    public ErpConfigDto getConfig(String companyId) {
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        CompanyErpConfig cfg = configRepository.findByCompanyId(companyId).orElse(null);
        CompanyErpSystem erp = cfg != null ? cfg.getErp() : CompanyErpSystem.MANUAL;
        Map<String, Object> config = cfg != null && cfg.getConfigEnc() != null
                ? decodificarComSeguranca(cfg.getConfigEnc()) : Map.of();

        return new ErpConfigDto(
                new CompanyRefDto(company.getId(), company.getName(), company.getStatus().name()),
                erp.name(), ErpFields.ERP_SYSTEMS.stream().map(Enum::name).toList(), FIELDS_POR_NOME,
                mascarar(erp, config),
                cfg == null ? null : new LastTestDto(cfg.getLastTestAt(), cfg.getLastTestOk(), cfg.getLastTestMessage()),
                cfg == null ? null : cfg.getUpdatedAt(), null);
    }

    private Map<String, Object> decodificarComSeguranca(String configEnc) {
        try {
            return erpCryptoService.decryptJson(configEnc);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, String> mascarar(CompanyErpSystem erp, Map<String, Object> config) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ErpField f : ErpFields.ERP_FIELDS.getOrDefault(erp, List.of())) {
            Object v = config.get(f.key());
            String texto = v == null ? "" : String.valueOf(v);
            out.put(f.key(), f.secret() ? (texto.isBlank() ? "" : "••••••") : texto);
        }
        return out;
    }

    @Transactional
    public ErpConfigDto setConfig(String companyId, String erpBruto, Map<String, Object> configBruto, CurrentUser actor) {
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        // A integração com ERPs externos é uma funcionalidade do plano PRO.
        planService.assertFeature(company, PlanFeatureFlag.ERP_INTEGRATION, "Integração com ERP");
        CompanyErpSystem erp = erpValido(erpBruto);
        if (company.getStatus() != CompanyStatus.APROVADA) {
            throw new BusinessRuleException("Só é possível configurar o ERP de empresas aprovadas.");
        }

        CompanyErpConfig prev = configRepository.findByCompanyId(companyId).orElse(null);
        CompanyErpSystem fromErp = prev != null ? prev.getErp() : CompanyErpSystem.MANUAL;
        Map<String, Object> config = configBruto == null ? Map.of() : configBruto;

        ErpIntegrationClient.Resultado integracao;
        CompanyErpConfig cfg = prev != null ? prev : new CompanyErpConfig(UUID.randomUUID().toString(), companyId, erp, null, Instant.now());

        if (!ErpFields.isRealErp(erp)) {
            // Sem ERP (Manual): limpa credenciais e desativa no microserviço.
            cfg.setErp(CompanyErpSystem.MANUAL);
            cfg.setConfigEnc(null);
            cfg.setLastTestAt(null);
            cfg.setLastTestOk(null);
            cfg.setLastTestMessage(null);
            configRepository.save(cfg);
            integracao = ErpFields.isRealErp(fromErp)
                    ? integrationClient.chamar("DELETE", "/credentials/tenants/" + companyId + "/" + fromErp, null)
                    : ErpIntegrationClient.Resultado.skipped(null);
        } else {
            List<ErpField> missing = ErpFields.ERP_FIELDS.getOrDefault(erp, List.of()).stream()
                    .filter(f -> f.required() && String.valueOf(config.getOrDefault(f.key(), "")).isBlank())
                    .toList();
            if (!missing.isEmpty()) {
                throw new BusinessRuleException("Preencha os campos obrigatórios: "
                        + missing.stream().map(ErpField::label).reduce((a, b) -> a + ", " + b).orElse(""));
            }
            // Se o utilizador não reenviou um segredo (deixou mascarado/vazio), mantém o anterior.
            Map<String, Object> merged = new HashMap<>(config);
            if (prev != null && prev.getConfigEnc() != null) {
                Map<String, Object> prevCfg = decodificarComSeguranca(prev.getConfigEnc());
                for (ErpField f : ErpFields.ERP_FIELDS.getOrDefault(erp, List.of())) {
                    if (f.secret()) {
                        Object v = config.get(f.key());
                        if (v == null || "••••••".equals(v)) merged.put(f.key(), prevCfg.getOrDefault(f.key(), ""));
                    }
                }
            }
            String configEnc = erpCryptoService.encryptJson(merged);
            cfg.setErp(erp);
            cfg.setConfigEnc(configEnc);
            cfg.setLastTestAt(null);
            cfg.setLastTestOk(null);
            cfg.setLastTestMessage(null);
            configRepository.save(cfg);

            integracao = integrationClient.chamar("PUT", "/credentials/tenants/" + companyId + "/" + erp,
                    Map.of("enabled", true, "config", merged));
            if (ErpFields.isRealErp(fromErp) && fromErp != erp) {
                integrationClient.chamar("DELETE", "/credentials/tenants/" + companyId + "/" + fromErp, null);
            }
        }

        String resultado = integracao.skipped() ? "guardado (microserviço não configurado)"
                : integracao.ok() ? "guardado e sincronizado"
                : "guardado; sync falhou (" + (integracao.message() != null ? integracao.message() : integracao.status()) + ")";
        auditar(companyId, "SET", fromErp, erp, actor, resultado);

        ErpConfigDto atual = getConfig(companyId);
        return new ErpConfigDto(atual.company(), atual.erp(), atual.systems(), atual.fields(), atual.config(),
                atual.lastTest(), atual.updatedAt(), !integracao.skipped() && integracao.ok());
    }

    private CompanyErpSystem erpValido(String erpBruto) {
        try {
            CompanyErpSystem erp = CompanyErpSystem.valueOf(erpBruto);
            if (!ErpFields.ERP_SYSTEMS.contains(erp)) throw new IllegalArgumentException();
            return erp;
        } catch (Exception e) {
            throw new BusinessRuleException("ERP inválido: " + erpBruto);
        }
    }

    @Transactional
    public ErpTestResultDto testConnection(String companyId, CurrentUser actor) {
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        planService.assertFeature(company, PlanFeatureFlag.ERP_INTEGRATION, "Integração com ERP");
        CompanyErpConfig cfg = configRepository.findByCompanyId(companyId).orElse(null);
        if (cfg == null || !ErpFields.isRealErp(cfg.getErp())) {
            throw new BusinessRuleException("Sem ERP configurado para testar (modo Manual).");
        }
        ErpIntegrationClient.Resultado res = integrationClient.chamar(
                "POST", "/credentials/tenants/" + companyId + "/" + cfg.getErp() + "/test", null);
        boolean ok = res.data() != null && res.data().path("ok").asBoolean(false);
        String message = res.skipped() ? res.message()
                : res.data() != null && res.data().hasNonNull("message") ? res.data().get("message").asText()
                : res.ok() ? "Sem resposta." : ("Erro " + (res.status() != null ? res.status() : "")).trim();

        Instant agora = Instant.now();
        cfg.setLastTestAt(agora);
        cfg.setLastTestOk(ok);
        cfg.setLastTestMessage(message);
        auditar(companyId, "TEST", null, cfg.getErp(), actor, (ok ? "OK" : "FALHOU") + " — " + message);

        return new ErpTestResultDto(ok, message, agora);
    }

    @Transactional(readOnly = true)
    public List<ErpConfigAuditDto> listAudits(String companyId) {
        return auditRepository.findByCompanyIdOrderByCreatedAtDesc(companyId, PageRequest.of(0, 50)).stream()
                .map(a -> new ErpConfigAuditDto(a.getId(), a.getCompanyId(), a.getAction(),
                        a.getFromErp() == null ? null : a.getFromErp().name(),
                        a.getToErp() == null ? null : a.getToErp().name(),
                        a.getActorUserId(), a.getActorName(), a.getResult(), a.getCreatedAt()))
                .toList();
    }

    /** Nunca lança — uma falha a registar a auditoria ERP não pode impedir a operação, tal como no Node. */
    private void auditar(String companyId, String action, CompanyErpSystem fromErp, CompanyErpSystem toErp,
                          CurrentUser actor, String result) {
        try {
            auditRepository.save(new CompanyErpConfigAudit(UUID.randomUUID().toString(), companyId, action,
                    fromErp, toErp, actor == null ? null : actor.id(), actor == null ? null : actor.name(),
                    result, Instant.now()));
        } catch (Exception e) {
            log.warn("erpConfig: falha ao gravar auditoria: {}", e.getMessage());
        }
    }
}
