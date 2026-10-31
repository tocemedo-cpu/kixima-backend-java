package ao.kixima.company;

import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ConflictException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.company.dto.OnboardingDto;
import ao.kixima.plan.PlanService;
import ao.kixima.storage.StorageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Cadastro empresarial progressivo. A conta já existe (ver {@link ao.kixima.auth.RegistoService}); aqui o administrador
 * da empresa preenche, em várias sessões se quiser, os MESMOS dados e documentos que o cadastro antigo pedia de uma vez
 * — só mudou o momento. Guardar nunca valida o conjunto: só {@link #submeter} aplica as regras de negócio
 * (documentos obrigatórios por tipo, apólice das Prestadoras, NIF único…), as mesmas de
 * {@link CompanyService#registerCompany}, e passa a empresa a PENDENTE para a análise da KIXIMA.
 *
 * Os dados que ainda não têm tabela própria — a apólice da Prestadora, que só passa a registo em
 * {@code supplier_to_kixima_policies} na submissão — ficam em {@code companies.onboarding_draft} (JSON).
 */
@Service
public class OnboardingService {

    /** Mesmo tipo de documento que o cadastro antigo recebia como «APOLICE_SEGURO» e o logótipo opcional. */
    public static final String APOLICE_SEGURO = "APOLICE_SEGURO";
    public static final String LOGOTIPO = "LOGOTIPO";

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final TypeReference<LinkedHashMap<String, String>> MAPA = new TypeReference<>() {
    };
    private static final List<String> CAMPOS_APOLICE = List.of("insurer", "policyNumber", "coverageAmount", "currency", "validFrom", "validUntil");

    private final CompanyRepository companyRepository;
    private final CompanyDocumentRepository documentRepository;
    private final SupplierToKiximaPolicyRepository policyRepository;
    private final PlanService planService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public OnboardingService(CompanyRepository companyRepository, CompanyDocumentRepository documentRepository,
                             SupplierToKiximaPolicyRepository policyRepository, PlanService planService,
                             StorageService storageService, ObjectMapper objectMapper) {
        this.companyRepository = companyRepository;
        this.documentRepository = documentRepository;
        this.policyRepository = policyRepository;
        this.planService = planService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    /** Campos que o utilizador pode guardar; {@code null} = não vem neste pedido (não toca), {@code ""} = limpar. */
    public record Alteracoes(String name, String taxId, String contactEmail, String contactPhone, String address, String city,
                             String province, String employees, String annualRevenueUsd, String insurer, String policyNumber,
                             String coverageAmount, String policyCurrency, String policyValidFrom, String policyValidUntil) {
    }

    // --- leitura ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public OnboardingDto obter(String companyId) {
        return montar(empresa(companyId));
    }

    // --- guardar -------------------------------------------------------------------------------------------------

    @Transactional
    public OnboardingDto guardar(String companyId, Alteracoes a) {
        Company c = empresaEditavel(companyId);

        if (a.name() != null) {
            String nome = a.name().trim();
            if (!nome.isEmpty() && nome.length() < 2) throw new ValidationException("name: use pelo menos 2 caracteres.");
            c.setName(nome.isEmpty() ? null : nome);
        }
        if (a.taxId() != null) {
            String nif = a.taxId().trim();
            if (!nif.isEmpty() && nif.length() < 3) throw new ValidationException("taxId: use pelo menos 3 caracteres.");
            if (!nif.isEmpty() && !nif.equals(c.getTaxId()) && companyRepository.existsByTaxId(nif)) {
                throw new ConflictException("Já existe uma empresa registada com este NIF.");
            }
            c.setTaxId(nif.isEmpty() ? null : nif);
        }
        if (a.contactEmail() != null) {
            String email = a.contactEmail().trim();
            if (!EMAIL.matcher(email).matches()) throw new ValidationException("contactEmail: indique um email válido.");
            c.setContactEmail(email);
        }
        if (a.contactPhone() != null) c.setContactPhone(textoOuNull(a.contactPhone()));
        if (a.address() != null) c.setAddress(textoOuNull(a.address()));
        if (a.city() != null) c.setCity(textoOuNull(a.city()));
        if (a.province() != null) c.setProvince(textoOuNull(a.province()));

        if (a.employees() != null || a.annualRevenueUsd() != null) {
            if (a.employees() != null) c.setEmployees(CompanyService.inteiroOuNull(a.employees()));
            if (a.annualRevenueUsd() != null) c.setAnnualRevenueUsd(CompanyService.decimalOuNull(a.annualRevenueUsd(), "annualRevenueUsd"));
            classificar(c);
        }

        guardarApolice(c, a);
        return montar(c);
    }

    private void guardarApolice(Company c, Alteracoes a) {
        boolean traz = a.insurer() != null || a.policyNumber() != null || a.coverageAmount() != null
                || a.policyCurrency() != null || a.policyValidFrom() != null || a.policyValidUntil() != null;
        if (!traz) return;
        if (c.getType() != CompanyType.FORNECEDOR) throw new ValidationException("A apólice de seguro só se aplica a empresas prestadoras.");

        LinkedHashMap<String, String> rascunho = lerRascunho(c);
        if (rascunho.isEmpty()) {
            // Primeira alteração: parte do que já existe (ex.: uma apólice submetida antes) para não perder o resto.
            Apolice atual = apoliceDaBase(c.getId()).orElse(null);
            if (atual != null) rascunho.putAll(atual.comoMapa());
        }
        if (a.insurer() != null) rascunho.put("insurer", a.insurer().trim());
        if (a.policyNumber() != null) rascunho.put("policyNumber", a.policyNumber().trim());
        if (a.coverageAmount() != null) {
            BigDecimal valor = CompanyService.decimalOuNull(a.coverageAmount(), "coverageAmount");
            if (valor != null && valor.signum() <= 0) throw new ValidationException("coverageAmount: tem de ser positivo.");
            rascunho.put("coverageAmount", valor == null ? "" : a.coverageAmount().trim());
        }
        if (a.policyCurrency() != null) rascunho.put("currency", a.policyCurrency().trim());
        if (a.policyValidFrom() != null) {
            CompanyService.dataOuNull(a.policyValidFrom(), "policyValidFrom");
            rascunho.put("validFrom", a.policyValidFrom().trim());
        }
        if (a.policyValidUntil() != null) {
            CompanyService.dataOuNull(a.policyValidUntil(), "policyValidUntil");
            rascunho.put("validUntil", a.policyValidUntil().trim());
        }
        escreverRascunho(c, rascunho);
    }

    // --- documentos ----------------------------------------------------------------------------------------------

    /** Envia (ou substitui) um documento: os de credenciamento do tipo de empresa, a apólice (Prestadoras) ou o logótipo. */
    @Transactional
    public OnboardingDto enviarDocumento(String companyId, String tipoBruto, MultipartFile file) {
        Company c = empresaEditavel(companyId);
        if (file == null || file.isEmpty()) throw new ValidationException("Escolha o ficheiro a enviar.");
        CompanyService.validarDocumento(file);
        String nomeOriginal = file.getOriginalFilename() == null ? "documento" : file.getOriginalFilename();

        if (LOGOTIPO.equals(tipoBruto)) {
            if (file.getContentType() == null || !file.getContentType().startsWith("image/")) {
                throw new ValidationException("O logótipo tem de ser uma imagem (PNG/JPG).");
            }
            c.setLogoUrl(storageService.saveFile(CompanyService.bytesDe(file), nomeOriginal, file.getContentType(), c.getId() + "-LOGO", "logos"));
        } else if (APOLICE_SEGURO.equals(tipoBruto)) {
            if (c.getType() != CompanyType.FORNECEDOR) throw new ValidationException("A apólice de seguro só se aplica a empresas prestadoras.");
            String url = storageService.saveFile(CompanyService.bytesDe(file), nomeOriginal, file.getContentType(), c.getId() + "-APOLICE", "documents");
            LinkedHashMap<String, String> rascunho = lerRascunho(c);
            if (rascunho.isEmpty()) apoliceDaBase(c.getId()).ifPresent(p -> rascunho.putAll(p.comoMapa()));
            rascunho.put("documentUrl", url);
            rascunho.put("documentName", nomeOriginal);
            escreverRascunho(c, rascunho);
        } else {
            DocumentType tipo;
            try {
                tipo = DocumentType.valueOf(tipoBruto);
            } catch (IllegalArgumentException e) {
                throw new ValidationException("Tipo de documento inválido.");
            }
            if (!CompanyService.REQUIRED_DOCS.getOrDefault(c.getType(), List.of()).contains(tipo)) {
                throw new ValidationException("Este documento não é exigido a este tipo de empresa.");
            }
            String url = storageService.saveFile(CompanyService.bytesDe(file), nomeOriginal, file.getContentType(), c.getId() + "-" + tipo.name(), "documents");
            documentRepository.findByCompanyIdOrderByTypeAsc(c.getId()).stream().filter(d -> d.getType() == tipo).forEach(documentRepository::delete);
            documentRepository.flush();
            documentRepository.save(new CompanyDocument(UUID.randomUUID().toString(), c.getId(), tipo, url, nomeOriginal, Instant.now()));
        }
        return montar(c);
    }

    // --- submissão -----------------------------------------------------------------------------------------------

    /** Aplica as regras de negócio do cadastro e passa a empresa a PENDENTE (análise da KIXIMA). */
    @Transactional
    public OnboardingDto submeter(String companyId) {
        Company c = empresaEditavel(companyId);
        List<String> pendencias = pendencias(c);
        if (!pendencias.isEmpty()) {
            throw new BusinessRuleException("O cadastro ainda não está completo. Falta: " + String.join("; ", pendencias) + ".");
        }
        // O NIF pode ter sido ocupado por outra empresa desde que foi guardado.
        classificar(c);

        if (c.getType() == CompanyType.FORNECEDOR) {
            LinkedHashMap<String, String> rascunho = lerRascunho(c);
            if (!rascunho.isEmpty()) {
                Instant agora = Instant.now();
                // A apólice corrigida substitui as anteriores ainda por decidir (ex.: empresa rejeitada que corrige e volta a submeter).
                policyRepository.findByCompanyId(c.getId()).stream().filter(p -> p.getStatus() == PolicyStatus.SUBMETIDA)
                        .forEach(p -> p.setStatus(PolicyStatus.REJEITADA));
                SupplierToKiximaPolicy nova = new SupplierToKiximaPolicy(UUID.randomUUID().toString(), c.getId(), rascunho.get("policyNumber"),
                        rascunho.get("insurer"), new BigDecimal(rascunho.get("coverageAmount").trim()),
                        CompanyService.vazio(rascunho.get("currency")) ? "AOA" : rascunho.get("currency"),
                        CompanyService.dataOuNull(rascunho.get("validFrom"), "policyValidFrom"),
                        CompanyService.dataOuNull(rascunho.get("validUntil"), "policyValidUntil"), agora);
                nova.setDocumentUrl(rascunho.get("documentUrl"));
                nova.setStatus(PolicyStatus.SUBMETIDA);
                policyRepository.save(nova);
                c.setOnboardingDraft(null);
            }
        }
        c.submeter(Instant.now());
        return montar(c);
    }

    // --- regras e montagem -----------------------------------------------------------------------------------------

    /** O que ainda falta para poder submeter — as mesmas exigências do cadastro antigo, por etapa. */
    private List<String> pendencias(Company c) {
        List<String> falta = new ArrayList<>();
        etapas(c).forEach(e -> falta.addAll(e.missing()));
        return falta;
    }

    private List<OnboardingDto.Etapa> etapas(Company c) {
        List<OnboardingDto.Etapa> etapas = new ArrayList<>();

        List<String> empresa = new ArrayList<>();
        if (CompanyService.vazio(c.getName())) empresa.add("Nome da empresa");
        if (CompanyService.vazio(c.getTaxId())) empresa.add("NIF");
        etapas.add(new OnboardingDto.Etapa("empresa", empresa.isEmpty(), empresa));

        List<String> contactos = new ArrayList<>();
        if (CompanyService.vazio(c.getContactEmail())) contactos.add("Email de contacto da empresa");
        etapas.add(new OnboardingDto.Etapa("contactos", contactos.isEmpty(), contactos));

        List<DocumentType> enviados = documentRepository.findByCompanyIdOrderByTypeAsc(c.getId()).stream().map(CompanyDocument::getType).toList();
        List<String> docs = CompanyService.REQUIRED_DOCS.getOrDefault(c.getType(), List.of()).stream()
                .filter(t -> !enviados.contains(t)).map(CompanyService.DOC_LABELS::get).toList();
        etapas.add(new OnboardingDto.Etapa("documentos", docs.isEmpty(), docs));

        if (c.getType() == CompanyType.FORNECEDOR) {
            Apolice ap = apoliceAtual(c);
            List<String> apolice = new ArrayList<>();
            if (CompanyService.vazio(ap.insurer())) apolice.add("Seguradora da apólice");
            if (CompanyService.vazio(ap.policyNumber())) apolice.add("Nº da apólice");
            if (CompanyService.vazio(ap.coverageAmount())) apolice.add("Cobertura da apólice");
            if (CompanyService.vazio(ap.validFrom()) || CompanyService.vazio(ap.validUntil())) apolice.add("Validade da apólice");
            if (CompanyService.vazio(ap.documentUrl())) apolice.add("Documento da apólice de seguro");
            etapas.add(new OnboardingDto.Etapa("apolice", apolice.isEmpty(), apolice));
        }
        return etapas;
    }

    private OnboardingDto montar(Company c) {
        List<CompanyDocument> enviados = documentRepository.findByCompanyIdOrderByTypeAsc(c.getId());
        List<DocumentType> obrigatorios = CompanyService.REQUIRED_DOCS.getOrDefault(c.getType(), List.of());
        List<OnboardingDto.Documento> documentos = obrigatorios.stream().map(t -> {
            Optional<CompanyDocument> d = enviados.stream().filter(x -> x.getType() == t).max(Comparator.comparing(CompanyDocument::getCreatedAt));
            return new OnboardingDto.Documento(t.name(), CompanyService.DOC_LABELS.get(t), true, d.isPresent(),
                    d.map(CompanyDocument::getOriginalName).orElse(null), d.map(CompanyDocument::getFileUrl).orElse(null),
                    d.map(CompanyDocument::getCreatedAt).orElse(null));
        }).toList();

        OnboardingDto.Empresa empresa = new OnboardingDto.Empresa(c.getName(), c.getTaxId(), c.getContactEmail(), c.getContactPhone(),
                c.getAddress(), c.getCity(), c.getProvince(), c.getCountry(), c.getEmployees(), c.getAnnualRevenueUsd(), c.getLogoUrl(),
                c.getTermsAcceptedAt() != null);
        OnboardingDto.Apolice apolice = c.getType() == CompanyType.FORNECEDOR ? apoliceAtual(c).paraDto() : null;
        List<OnboardingDto.Etapa> etapas = etapas(c);
        List<String> pendencias = pendencias(c);
        boolean termos = c.getTermsAcceptedAt() != null;
        return new OnboardingDto(c.getStatus().name(), c.getType().name(), c.editavelNoCadastro(), c.getSubmittedAt(), c.getApprovedAt(),
                c.getRejectedAt(), c.getRejectionReason(), empresa, apolice, documentos, etapas, pendencias,
                c.editavelNoCadastro() && pendencias.isEmpty() && termos);
    }

    // --- apólice em rascunho ---------------------------------------------------------------------------------------

    private record Apolice(String insurer, String policyNumber, String coverageAmount, String currency, String validFrom,
                           String validUntil, String documentUrl, String documentName) {
        LinkedHashMap<String, String> comoMapa() {
            LinkedHashMap<String, String> m = new LinkedHashMap<>();
            m.put("insurer", insurer);
            m.put("policyNumber", policyNumber);
            m.put("coverageAmount", coverageAmount);
            m.put("currency", currency);
            m.put("validFrom", validFrom);
            m.put("validUntil", validUntil);
            if (documentUrl != null) m.put("documentUrl", documentUrl);
            if (documentName != null) m.put("documentName", documentName);
            return m;
        }

        OnboardingDto.Apolice paraDto() {
            return new OnboardingDto.Apolice(insurer, policyNumber, coverageAmount, currency, validFrom, validUntil, documentUrl, documentName);
        }
    }

    /** O que o utilizador vê: o rascunho, ou — se ainda não mexeu nele — a apólice já submetida, se houver. */
    private Apolice apoliceAtual(Company c) {
        LinkedHashMap<String, String> r = lerRascunho(c);
        if (r.isEmpty()) {
            Optional<Apolice> existente = apoliceDaBase(c.getId());
            if (existente.isPresent()) return existente.get();
        }
        return new Apolice(r.get("insurer"), r.get("policyNumber"), r.get("coverageAmount"),
                CompanyService.vazio(r.get("currency")) ? "AOA" : r.get("currency"), r.get("validFrom"), r.get("validUntil"),
                r.get("documentUrl"), r.get("documentName"));
    }

    /** A apólice mais recente ainda válida para a análise (submetida ou aprovada); uma rejeitada tem de ser refeita. */
    private Optional<Apolice> apoliceDaBase(String companyId) {
        return policyRepository.findByCompanyId(companyId).stream()
                .filter(p -> p.getStatus() == PolicyStatus.SUBMETIDA || p.getStatus() == PolicyStatus.APROVADA)
                .max(Comparator.comparing(SupplierToKiximaPolicy::getCreatedAt))
                .map(p -> new Apolice(p.getInsurer(), p.getPolicyNumber(), p.getCoverageAmount().stripTrailingZeros().toPlainString(),
                        p.getCurrency(), dia(p.getValidFrom()), dia(p.getValidUntil()), p.getDocumentUrl(), null));
    }

    private static String dia(Instant i) {
        return i == null ? null : i.toString().substring(0, 10);
    }

    private LinkedHashMap<String, String> lerRascunho(Company c) {
        if (CompanyService.vazio(c.getOnboardingDraft())) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(c.getOnboardingDraft(), MAPA);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rascunho do cadastro ilegível.", e);
        }
    }

    private void escreverRascunho(Company c, LinkedHashMap<String, String> rascunho) {
        try {
            c.setOnboardingDraft(rascunho.isEmpty() ? null : objectMapper.writeValueAsString(rascunho));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Não foi possível guardar o rascunho do cadastro.", e);
        }
    }

    // --- utilitários -------------------------------------------------------------------------------------------------

    private Company empresa(String companyId) {
        return companyRepository.findById(companyId == null ? "" : companyId).orElseThrow(() -> new NotFoundException("Empresa"));
    }

    private Company empresaEditavel(String companyId) {
        Company c = empresa(companyId);
        if (!c.editavelNoCadastro()) {
            throw new BusinessRuleException(c.getStatus() == CompanyStatus.PENDENTE
                    ? "O cadastro está em análise pela KIXIMA e não pode ser alterado."
                    : "O cadastro desta empresa já não pode ser alterado aqui.");
        }
        return c;
    }

    /** Dimensão e plano a partir dos trabalhadores/volume de negócios — a regra de {@code registerCompany}. */
    private void classificar(Company c) {
        CompanySize size = planService.classify(c.getEmployees(), c.getAnnualRevenueUsd());
        CompanyPlan plano = planService.requiredPlan(size) == CompanyPlan.PRO ? CompanyPlan.PRO : CompanyPlan.BASICO;
        c.setSize(size);
        c.setPlan(plano);
        c.setSearchRank(planService.rankDoPlano(plano));
    }

    private static String textoOuNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
