package ao.kixima.addon;

import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Espelha só a GUARDA de backend/src/services/addonService.js —
 * {@link #assertAddon}: lança se o add-on não estiver ATIVO e dentro da
 * validade paga. É o que poRoboRoutes.js/poRoboService.js exigem antes de
 * qualquer operação do robot.
 *
 * O fluxo de cobrança (pedir/submeterComprovativo/confirmar/cancelar/fila/
 * catalogo/estado) está em {@link ao.kixima.cobranca.AddonCobrancaService}.
 */
@Service
public class AddonService {

    public static final String PO_ROBOT = "PO_ROBOT";

    private static final DateTimeFormatter DATA = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Espelha ADDONS — o primeiro add-on é o Automatic PO Robot. Preço configurável por ambiente, nunca hardcoded. */
    public record Definicao(String label, String requerPlano, BigDecimal valorUsd, String periodo) {
    }

    private final Map<String, Definicao> addons;
    private final CompanyAddonRepository companyAddonRepository;

    public AddonService(CompanyAddonRepository companyAddonRepository,
                         @Value("${kixima.addons.po-robot.valor-usd:200}") BigDecimal poRobotValorUsd,
                         @Value("${kixima.addons.po-robot.periodo:MENSAL}") String poRobotPeriodo) {
        this.companyAddonRepository = companyAddonRepository;
        this.addons = Map.of(PO_ROBOT, new Definicao("Automatic PO Robot", "PRO", poRobotValorUsd, poRobotPeriodo));
    }

    /** As chaves dos add-ons existentes (ordem estável), para o catálogo. */
    public java.util.Set<String> chaves() {
        return new java.util.TreeSet<>(addons.keySet());
    }

    public Definicao definicao(String addonKey) {
        Definicao def = addons.get(addonKey);
        if (def == null) {
            throw new ValidationException("Add-on desconhecido: \"" + addonKey + "\". Os add-ons são: "
                    + String.join(", ", addons.keySet()) + ".");
        }
        return def;
    }

    /**
     * Ativo de verdade: status ATIVO E ainda dentro da validade paga. A
     * validade NUNCA muda o status sozinha (nenhum job a fazer isso) — é
     * verificada a cada leitura, mesmo princípio de PlanService.estadoSubscricao.
     */
    public boolean aindaValido(CompanyAddon addon, Instant agora) {
        if (addon == null || addon.getStatus() != CompanyAddonStatus.ATIVO) return false;
        if (addon.getValidoAte() == null) return true; // add-ons ativados antes desta correção, sem validade guardada
        return !addon.getValidoAte().isBefore(agora);
    }

    @Transactional(readOnly = true)
    public void assertAddon(String companyId, String addonKey, String label) {
        Definicao def = definicao(addonKey);
        CompanyAddon addon = companyAddonRepository.findByCompanyIdAndAddonKey(companyId, addonKey).orElse(null);
        if (aindaValido(addon, Instant.now())) return;

        String nome = label == null ? def.label() : label;
        boolean venceu = addon != null && addon.getStatus() == CompanyAddonStatus.ATIVO && addon.getValidoAte() != null;
        throw new BusinessRuleException(venceu
                ? "\"" + nome + "\" venceu em " + DATA.format(addon.getValidoAte().atZone(ZoneOffset.UTC))
                        + " — peça a renovação para continuar a usar."
                : "\"" + nome + "\" é um add-on pago (" + def.valorUsd().stripTrailingZeros().toPlainString() + " USD/"
                        + def.periodo().toLowerCase() + ") e ainda não está ativo para esta empresa.");
    }

    public void assertAddon(String companyId, String addonKey) {
        assertAddon(companyId, addonKey, null);
    }
}
