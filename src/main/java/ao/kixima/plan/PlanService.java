package ao.kixima.plan;

import ao.kixima.common.Decimais;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import ao.kixima.common.error.PlanRequiredException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyPlan;
import ao.kixima.company.CompanySize;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.time.Instant;
import java.util.Map;

/**
 * Espelha backend/src/services/planService.js — dimensão da empresa
 * (critério MPME, Lei n.º 30/11) e os 3 planos de subscrição (BASE, CORE,
 * PRO). Fonte única de verdade para o que cada plano inclui: qualquer
 * limite/funcionalidade que dependa do plano lê-se daqui.
 *
 * As duas metades do ficheiro Node estão cá: LIMITES/FUNCIONALIDADES
 * ({@link #assertFeature}, limites por plano) e PREÇOS ({@link #preco},
 * {@link #configurarPrecos} — mesmas variáveis {@code KIXIMA_PRECO_*}, com
 * o equivalente mensal calculado).
 */
@Service
public class PlanService {

    private static final Map<CompanyPlan, PlanFeatures> FEATURES = Map.of(
            CompanyPlan.BASE, new PlanFeatures(null, 0, false, 2, false, false, 1, 3, 3, 3,
                    false, false, false, false, true, true, false),
            CompanyPlan.CORE, new PlanFeatures(null, 1, false, 5, true, false, 3, 10, 20, 12,
                    false, false, false, false, true, true, false),
            CompanyPlan.PRO, new PlanFeatures(null, 2, true, null, true, true, 6, 10, null, null,
                    true, true, true, true, true, true, true)
    );

    private static final CompanyPlan[] ESCADA = {CompanyPlan.BASE, CompanyPlan.CORE, CompanyPlan.PRO};
    private static final long DIA_MS = 24L * 60 * 60 * 1000;

    private final BigDecimal seatPriceCapUsd;
    private final int gracePeriodDays;
    private final int limiarAExpirarDias;

    /** Espelha `preco()` — com o equivalente mensal CALCULADO, nunca escrito à mão. */
    public record Preco(@JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal valorUsd, String periodo, int meses, @JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal porMesUsd) {
    }

    public static final Map<String, Integer> MESES_DO_PERIODO = Map.of("MENSAL", 1, "TRIMESTRAL", 3, "SEMESTRAL", 6, "ANUAL", 12);

    private final Map<CompanyPlan, Preco> precos = new java.util.EnumMap<>(CompanyPlan.class);

    @org.springframework.beans.factory.annotation.Autowired
    void configurarPrecos(@Value("${kixima.plan.precos.base.valor-usd:100}") BigDecimal baseUsd,
                          @Value("${kixima.plan.precos.base.periodo:TRIMESTRAL}") String basePeriodo,
                          @Value("${kixima.plan.precos.core.valor-usd:100}") BigDecimal coreUsd,
                          @Value("${kixima.plan.precos.core.periodo:MENSAL}") String corePeriodo,
                          @Value("${kixima.plan.precos.pro.valor-usd:5000}") BigDecimal proUsd,
                          @Value("${kixima.plan.precos.pro.periodo:ANUAL}") String proPeriodo) {
        precos.put(CompanyPlan.BASE, preco(baseUsd, basePeriodo));
        precos.put(CompanyPlan.CORE, preco(coreUsd, corePeriodo));
        precos.put(CompanyPlan.PRO, preco(proUsd, proPeriodo));
    }

    private static Preco preco(BigDecimal valor, String periodo) {
        int meses = MESES_DO_PERIODO.getOrDefault(periodo, 1);
        return new Preco(valor, periodo, meses, valor.divide(BigDecimal.valueOf(meses), 2, java.math.RoundingMode.HALF_UP));
    }

    /** Preço de um plano (BASICO normaliza para CORE). */
    public Preco preco(CompanyPlan plan) {
        return precos.get(normalizarPlano(plan));
    }

    public List<CompanyPlan> escada() {
        return List.of(ESCADA);
    }

    /** `tabela()` — todos os planos com preço e funcionalidades, para a página pública. Um sítio só. */
    public List<Map<String, Object>> tabela() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (CompanyPlan plano : ESCADA) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("plano", plano.name());
            m.put("preco", preco(plano));
            m.put("features", features(plano));
            out.add(m);
        }
        return out;
    }

    public PlanService(@Value("${kixima.plan.seat-price-cap-usd:100}") BigDecimal seatPriceCapUsd,
                        @Value("${kixima.plan.grace-period-days:7}") int gracePeriodDays,
                        @Value("${kixima.plan.limiar-a-expirar-dias:30}") int limiarAExpirarDias) {
        this.seatPriceCapUsd = seatPriceCapUsd;
        this.gracePeriodDays = gracePeriodDays;
        this.limiarAExpirarDias = limiarAExpirarDias;
    }

    public BigDecimal seatPriceCapUsd() {
        return seatPriceCapUsd;
    }

    public record SupplierDevAccessFee(@JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal amountUsd, String currency, boolean dueOnSubmission, boolean remainderCustom) {
    }

    /**
     * Taxa de acesso ao programa Supplier Development — a mesma taxa de
     * acesso das pequenas empresas, cobrada logo na submissão da
     * candidatura (o resto do programa é orçamentado depois, caso a caso).
     */
    public SupplierDevAccessFee supplierDevAccessFee() {
        return new SupplierDevAccessFee(seatPriceCapUsd, "USD", true, true);
    }

    public int gracePeriodDays() {
        return gracePeriodDays;
    }

    /** Sem dados declarados assume PEQUENA (o mais permissivo) — o Admin confirma/corrige na due diligence. */
    public CompanySize classify(Integer employees, BigDecimal annualRevenueUsd) {
        if (employees == null && annualRevenueUsd == null) return CompanySize.PEQUENA;
        if (okDimensao(employees, annualRevenueUsd, 9, 250_000)) return CompanySize.MICRO;
        if (okDimensao(employees, annualRevenueUsd, 99, 3_000_000)) return CompanySize.PEQUENA;
        if (okDimensao(employees, annualRevenueUsd, 200, 10_000_000)) return CompanySize.MEDIA;
        return CompanySize.GRANDE;
    }

    private boolean okDimensao(Integer employees, BigDecimal annualRevenueUsd, int maxEmployees, long maxRevenueUsd) {
        boolean okEmployees = employees == null || employees <= maxEmployees;
        boolean okRevenue = annualRevenueUsd == null || annualRevenueUsd.compareTo(BigDecimal.valueOf(maxRevenueUsd)) <= 0;
        return okEmployees && okRevenue;
    }

    public CompanyPlan requiredPlan(CompanySize size) {
        return size == CompanySize.GRANDE ? CompanyPlan.PRO : CompanyPlan.BASE;
    }

    public boolean planAllowed(CompanySize size, CompanyPlan plan) {
        return indiceNaEscada(normalizarPlano(plan)) >= indiceNaEscada(requiredPlan(size));
    }

    /** BASICO é histórico (migrado para CORE) — nunca perde funcionalidades por um plano desconhecido. */
    public CompanyPlan normalizarPlano(CompanyPlan plan) {
        if (plan == null || plan == CompanyPlan.BASICO) return CompanyPlan.CORE;
        return FEATURES.containsKey(plan) ? plan : CompanyPlan.BASE;
    }

    private int indiceNaEscada(CompanyPlan plan) {
        for (int i = 0; i < ESCADA.length; i++) if (ESCADA[i] == plan) return i;
        return 0;
    }

    public PlanFeatures features(CompanyPlan plan) {
        return FEATURES.get(normalizarPlano(plan));
    }

    public boolean hasFeature(CompanyPlan plan, PlanFeatureFlag flag) {
        return flag.lido(features(plan));
    }

    public Integer limite(CompanyPlan plan, PlanLimit limit) {
        return limit.lido(features(plan));
    }

    public int rankDoPlano(CompanyPlan plan) {
        return features(plan).posicaoNaPesquisa();
    }

    /**
     * Patamar de urgência da subscrição desta empresa, agora — NUNCA
     * persistido, calculado a cada leitura a partir de
     * {@code planoValidoAte}, para nunca poder ficar dessincronizado.
     */
    public SubscriptionState estadoSubscricao(Company company, Instant agora) {
        Instant validoAte = company.getPlanoValidoAte();
        if (validoAte == null) return SubscriptionState.ATIVA;
        long diasAteVencer = (long) Math.ceil((validoAte.toEpochMilli() - agora.toEpochMilli()) / (double) DIA_MS);
        if (diasAteVencer > limiarAExpirarDias) return SubscriptionState.ATIVA;
        if (diasAteVencer > 0) return SubscriptionState.A_EXPIRAR;
        if (-diasAteVencer <= gracePeriodDays) return SubscriptionState.GRACE;
        return SubscriptionState.RESTRITA;
    }

    public SubscriptionState estadoSubscricao(Company company) {
        return estadoSubscricao(company, Instant.now());
    }

    /**
     * Guarda de LIMITE: lança se o próximo já ultrapassa o que o plano
     * inclui. A conta inclui o que já está em uso — quem chama decide o
     * que entra (ex.: utilizadores ativos + convites por aceitar).
     */
    public void assertLimite(Company company, PlanLimit limit, int usadoAgora, String label) {
        Integer max = limite(company.getPlan(), limit);
        if (max == null) return;
        if (usadoAgora < max) return;
        CompanyPlan plano = normalizarPlano(company.getPlan());
        CompanyPlan seguinte = plano == CompanyPlan.BASE ? CompanyPlan.CORE : CompanyPlan.PRO;
        throw new PlanRequiredException(
                "O plano " + plano + " inclui " + max + " " + label + ". Já tem " + usadoAgora + ". "
                        + "O plano " + seguinte + " aumenta este limite.",
                seguinte.name());
    }

    public CompanyPlan planoQueInclui(PlanFeatureFlag flag) {
        for (CompanyPlan plano : ESCADA) if (flag.lido(FEATURES.get(plano))) return plano;
        return null;
    }

    /**
     * Guarda de funcionalidade: verifica TAMBÉM se a subscrição está
     * RESTRITA — o mesmo conjunto de casos ("recursos premium") que o
     * Node também restringe quando a empresa deixa de pagar.
     */
    public void assertFeature(Company company, PlanFeatureFlag flag, String label) {
        if (estadoSubscricao(company) == SubscriptionState.RESTRITA) {
            throw new PlanRequiredException(
                    "A subscrição da sua empresa está vencida há mais de " + gracePeriodDays + " dias. "
                            + "Regularize o pagamento para voltar a usar \"" + label + "\".",
                    normalizarPlano(company.getPlan()).name());
        }
        if (!hasFeature(company.getPlan(), flag)) {
            CompanyPlan necessario = planoQueInclui(flag);
            String necessarioNome = necessario == null ? "PRO" : necessario.name();
            throw new PlanRequiredException(
                    "Esta funcionalidade (" + label + ") não está incluída no plano "
                            + normalizarPlano(company.getPlan()) + ". Faz parte do plano " + necessarioNome + ".",
                    necessarioNome);
        }
    }

    public record MonthlyAccessCost(int seats, @JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal seatPriceUsd, @JsonSerialize(using = Decimais.ComoNumeroJs.class) BigDecimal amountUsd, String currency) {
    }

    public MonthlyAccessCost monthlyAccessCost(int activeUsers, BigDecimal seatPriceUsd) {
        int seats = Math.max(0, activeUsers);
        BigDecimal price = seatPriceUsd == null ? seatPriceCapUsd : seatPriceUsd.min(seatPriceCapUsd);
        BigDecimal amount = price.multiply(BigDecimal.valueOf(seats)).setScale(2, java.math.RoundingMode.HALF_UP);
        return new MonthlyAccessCost(seats, price, amount, "USD");
    }
}
