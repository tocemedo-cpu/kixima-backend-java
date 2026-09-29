package ao.kixima.painel;

import ao.kixima.common.Decimais;

import ao.kixima.common.error.NotFoundException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyDocumentRepository;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.SupplierToKiximaPolicyRepository;
import ao.kixima.company.dto.CompanyDto;
import ao.kixima.contract.Contract;
import ao.kixima.contract.ContractRepository;
import ao.kixima.contract.ContractStatus;
import ao.kixima.invoice.Invoice;
import ao.kixima.invoice.InvoiceStatus;
import ao.kixima.po.PoStatus;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderRepository;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Espelha companyAdminService.js — agregações de leitura das telas do Company
 * Admin (Dashboard, Organização, Atividades, Relatórios, Configurações). As
 * respostas são mapas construídos explicitamente com a MESMA forma do Node.
 */
@Service
public class CompanyAdminPanelService {

    static final Set<PoStatus> RECEIVED = Set.of(PoStatus.RECEBIDA_CONFORME, PoStatus.RECEBIDA_COM_DIVERGENCIA, PoStatus.CONCLUIDA);

    /** Mapeamento estado da PO → (tipo, estado da atividade, módulo) das Atividades. */
    private record Atividade(String type, String status, String module) {
    }

    private static final Map<PoStatus, Atividade> STATUS = Map.ofEntries(
            Map.entry(PoStatus.AGUARDANDO_APROVACAO, new Atividade("Aprovação", "PENDENTE", "Ordens de Compra")),
            Map.entry(PoStatus.APROVADA, new Atividade("Aprovação", "CONCLUIDA", "Ordens de Compra")),
            Map.entry(PoStatus.AGUARDANDO_PAGAMENTO, new Atividade("Financeiro", "PENDENTE", "Financeiro")),
            Map.entry(PoStatus.PAGA, new Atividade("Financeiro", "CONCLUIDA", "Financeiro")),
            Map.entry(PoStatus.EM_EXECUCAO, new Atividade("Acompanhamento", "ANDAMENTO", "Entregas")),
            Map.entry(PoStatus.ENTREGUE, new Atividade("Acompanhamento", "ANDAMENTO", "Entregas")),
            Map.entry(PoStatus.RECEBIDA_CONFORME, new Atividade("Conclusão", "CONCLUIDA", "Recepção")),
            Map.entry(PoStatus.RECEBIDA_COM_DIVERGENCIA, new Atividade("Divergência", "ATRASADA", "Recepção")),
            Map.entry(PoStatus.CONCLUIDA, new Atividade("Conclusão", "CONCLUIDA", "Ordens de Compra")),
            Map.entry(PoStatus.REJEITADA, new Atividade("Rejeição", "ATRASADA", "Ordens de Compra")),
            Map.entry(PoStatus.RECUSADA_FORNECEDOR, new Atividade("Rejeição", "ATRASADA", "Ordens de Compra")));
    private static final Atividade ATIVIDADE_OMISSAO = new Atividade("Atualização", "CONCLUIDA", "Ordens de Compra");

    static final Map<String, Object> DEFAULT_SETTINGS = new LinkedHashMap<>();

    static {
        DEFAULT_SETTINGS.put("aprovacaoObrigatoria", true);
        DEFAULT_SETTINGS.put("assinaturaDigital", false);
        DEFAULT_SETTINGS.put("historicoAlteracoes", true);
        DEFAULT_SETTINGS.put("backupAutomatico", true);
        DEFAULT_SETTINGS.put("lembretesPrazos", true);
        DEFAULT_SETTINGS.put("valoresSemImpostos", false);
    }

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final ContractRepository contractRepository;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final CompanyDocumentRepository documentRepository;
    private final SupplierToKiximaPolicyRepository policyRepository;
    private final ObjectMapper objectMapper;

    public CompanyAdminPanelService(PurchaseOrderRepository purchaseOrderRepository, ContractRepository contractRepository,
                                    UserRepository userRepository, CompanyRepository companyRepository,
                                    CompanyDocumentRepository documentRepository, SupplierToKiximaPolicyRepository policyRepository,
                                    ObjectMapper objectMapper) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.contractRepository = contractRepository;
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.documentRepository = documentRepository;
        this.policyRepository = policyRepository;
        this.objectMapper = objectMapper;
    }

    /** `num()` do companyAdminService.js: as somas saem como número. */
    static Number soma(List<PurchaseOrder> os, Function<PurchaseOrder, BigDecimal> f) {
        return Decimais.numero(os.stream().map(f).filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    static Map<String, Object> mapa(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String contraparte(PurchaseOrder o, String companyId) {
        Company c = o.getBuyerCompanyId().equals(companyId) ? o.getSupplierCompany() : o.getBuyerCompany();
        return c == null ? null : c.getName();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(String companyId) {
        List<PurchaseOrder> orders = purchaseOrderRepository.findEnvolvendoEmpresaOrderByUpdatedAtDesc(companyId);
        List<Contract> contracts = contractRepository.findDaEmpresa(companyId);
        List<User> users = userRepository.findByCompanyId(companyId);
        Company company = companyRepository.findById(companyId).orElse(null);

        // Volume de Operações — últimos 6 meses (soma de totais de PO por mês).
        List<Map<String, Object>> volume = Meses.ultimosSeis().stream().map(m -> mapa("label", m.label(),
                "value", soma(orders.stream().filter(o -> m.contem(o.getCreatedAt())).toList(), PurchaseOrder::getTotalAmount))).toList();

        List<Map<String, Object>> recent = orders.stream().limit(5).map(o -> mapa("reference", o.getReference(),
                "status", o.getStatus().name(), "at", o.getUpdatedAt(), "party", contraparte(o, companyId))).toList();

        return mapa(
                "kpis", mapa(
                        "pedidos", orders.size(),
                        "emExecucao", orders.stream().filter(o -> o.getStatus() == PoStatus.EM_EXECUCAO || o.getStatus() == PoStatus.ENTREGUE).count(),
                        "volumeNegocios", soma(orders, PurchaseOrder::getTotalAmount),
                        "aprovarPO", orders.stream().filter(o -> o.getStatus() == PoStatus.AGUARDANDO_APROVACAO && o.getBuyerCompanyId().equals(companyId)).count()),
                "resumo", mapa(
                        "usuariosAtivos", users.stream().filter(User::isActive).count(),
                        "contratosAtivos", contracts.stream().filter(c -> c.getStatus() == ContractStatus.ATIVO).count(),
                        "totalContratos", contracts.size(),
                        "concluidas", orders.stream().filter(o -> RECEIVED.contains(o.getStatus())).count(),
                        "compliance", company != null && company.isVerified() ? 100 : 80),
                "volume", volume,
                "recent", recent);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> organizacao(String companyId) {
        Company company = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        return mapa("company", CompanyDto.de(company),
                "summary", mapa("users", userRepository.countByCompanyId(companyId),
                        "contracts", contractRepository.findDaEmpresa(companyId).size(),
                        "documents", documentRepository.countByCompanyId(companyId),
                        "certifications", policyRepository.countByCompanyId(companyId)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> activities(String companyId, String filter) {
        List<PurchaseOrder> orders = purchaseOrderRepository.findEnvolvendoEmpresaOrderByUpdatedAtDesc(companyId).stream().limit(60).toList();
        Map<String, String> nomes = userRepository.findAllById(orders.stream().map(PurchaseOrder::getCreatedById).filter(id -> id != null).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getName));
        List<Map<String, Object>> items = new ArrayList<>();
        for (PurchaseOrder o : orders) {
            Atividade m = STATUS.getOrDefault(o.getStatus(), ATIVIDADE_OMISSAO);
            // A descrição NÃO se compõe aqui: vão as partes (tipo + estado da PO) e o cliente compõe-nas já traduzidas.
            items.add(mapa("title", "PO " + o.getReference(), "poStatus", o.getStatus().name(),
                    "module", m.module(), "type", m.type(), "status", m.status(), "relatedTo", o.getReference(),
                    "party", contraparte(o, companyId), "responsavel", nomes.get(o.getCreatedById()), "at", o.getUpdatedAt()));
        }
        if (filter != null && !filter.isBlank() && !"TODOS".equals(filter)) {
            items = items.stream().filter(i -> filter.equals(i.get("status"))).toList();
        }
        List<String> src = orders.stream().map(o -> STATUS.getOrDefault(o.getStatus(), ATIVIDADE_OMISSAO).status()).toList();
        return mapa("kpis", mapa("total", orders.size(),
                        "concluidas", src.stream().filter("CONCLUIDA"::equals).count(),
                        "pendentes", src.stream().filter("PENDENTE"::equals).count(),
                        "andamento", src.stream().filter("ANDAMENTO"::equals).count(),
                        "atrasadas", src.stream().filter("ATRASADA"::equals).count()),
                "items", items);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> reports(String companyId) {
        List<PurchaseOrder> orders = purchaseOrderRepository.findEnvolvendoEmpresaOrderByUpdatedAtDesc(companyId);
        List<Contract> contracts = contractRepository.findDaEmpresa(companyId);

        List<Map<String, Object>> series = Meses.ultimosSeis().stream().map(m -> {
            List<PurchaseOrder> os = orders.stream().filter(o -> m.contem(o.getCreatedAt())).toList();
            return mapa("label", m.label(),
                    "receitas", soma(os.stream().filter(o -> faturaPaga(o.getInvoice())).toList(), o -> o.getInvoice().getAmount()),
                    "despesas", soma(os, PurchaseOrder::getTotalAmount),
                    "contratos", contracts.stream().filter(c -> m.contem(c.getCreatedAt())).count());
        }).toList();

        List<Map<String, Object>> catalog = List.of(
                mapa("key", "financeiro", "name", "Relatório Financeiro", "module", "Financeiro", "desc", "Resumo de faturas e pagamentos", "format", "PDF"),
                mapa("key", "ordens", "name", "Relatório de Ordens de Compra", "module", "Ordens", "desc", "POs por estado e valor", "format", "Excel"),
                mapa("key", "contratos", "name", "Relatório de Contratos", "module", "Contratos", "desc", "Estado e validade dos contratos", "format", "PDF"),
                mapa("key", "atividades", "name", "Atividade da Empresa", "module", "Atividades", "desc", "Ações e acessos recentes", "format", "PDF"));
        return mapa("kpis", mapa("gerados", catalog.size(), "ordens", orders.size(), "contratos", contracts.size(),
                        "faturado", soma(orders.stream().filter(o -> faturaPaga(o.getInvoice())).toList(), o -> o.getInvoice().getAmount())),
                "series", series, "catalog", catalog);
    }

    private static boolean faturaPaga(Invoice i) {
        return i != null && i.getStatus() == InvoiceStatus.PAGA;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSettings(String companyId) {
        Company c = companyRepository.findById(companyId).orElse(null);
        Map<String, Object> guardadas = lerSettings(c);
        return guardadas == null ? new LinkedHashMap<>(DEFAULT_SETTINGS) : guardadas;
    }

    @Transactional
    public Map<String, Object> saveSettings(String companyId, Map<String, Object> settings) {
        Company c = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa"));
        Map<String, Object> merged = new LinkedHashMap<>(DEFAULT_SETTINGS);
        if (settings != null) merged.putAll(settings);
        try {
            c.setSettings(objectMapper.writeValueAsString(merged));
        } catch (Exception e) {
            throw new IllegalStateException("Falha a serializar as preferências.", e);
        }
        return merged;
    }

    private Map<String, Object> lerSettings(Company c) {
        if (c == null || c.getSettings() == null || c.getSettings().isBlank()) return null;
        try {
            return objectMapper.readValue(c.getSettings(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }
}
