package ao.kixima.faturacao;

import ao.kixima.common.error.ValidationException;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.Map;

import static ao.kixima.security.AdminArea.FATURACAO;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;
import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.FORNECEDOR;

/**
 * Espelha o troço de backend/src/routes/faturacaoRoutes.js que não é AGT:
 * integridade da cadeia, SAF-T (AO) e métricas. Os endpoints AGT
 * (agt-payload, agt-estado, agt-serie-payload, agt-series-fe) vivem em
 * {@link ao.kixima.agt.AgtController}, no mesmo prefixo.
 */
@RestController
@RequestMapping("/api/faturacao")
public class FaturacaoController {

    private final CadeiaIntegridadeService cadeiaIntegridadeService;
    private final SaftService saftService;
    private final MetricasService metricasService;

    public FaturacaoController(CadeiaIntegridadeService cadeiaIntegridadeService, SaftService saftService, MetricasService metricasService) {
        this.cadeiaIntegridadeService = cadeiaIntegridadeService;
        this.saftService = saftService;
        this.metricasService = metricasService;
    }

    /** O Fornecedor só pede o SEU; o Admin do Sistema tem de indicar de qual empresa. */
    private static String resolverEmpresaFornecedora(String supplierCompanyIdQuery) {
        CurrentUser user = CurrentUserHolder.get();
        if (user.role() == ADMIN_SISTEMA) {
            if (supplierCompanyIdQuery == null || supplierCompanyIdQuery.isBlank()) {
                throw new ValidationException("Indique a empresa fornecedora (supplierCompanyId).");
            }
            return supplierCompanyIdQuery;
        }
        return user.companyId();
    }

    @GetMapping("/integridade")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FATURACAO)
    public Map<String, Object> integridade(@RequestParam(required = false) String serie, @RequestParam(required = false) String ano) {
        int anoEfetivo = Instant.now().atZone(ZoneOffset.UTC).getYear();
        if (ano != null && !ano.isBlank()) {
            try {
                int n = Integer.parseInt(ano.trim());
                if (n != 0) anoEfetivo = n;
            } catch (NumberFormatException ignored) {
                // Number('abc') → NaN → ano atual
            }
        }
        return cadeiaIntegridadeService.verificarCadeia(serie == null || serie.isBlank() ? null : serie, anoEfetivo);
    }

    @GetMapping("/saft")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN, ADMIN_SISTEMA})
    @RequirePermission(FATURACAO)
    public ResponseEntity<byte[]> saft(@RequestParam(required = false) String de, @RequestParam(required = false) String ate,
                                       @RequestParam(required = false) String supplierCompanyId) {
        SaftService.Resultado r = saftService.gerar(de, ate, resolverEmpresaFornecedora(supplierCompanyId));
        @SuppressWarnings("unchecked")
        Map<String, Object> periodo = (Map<String, Object>) r.resumo().get("periodo");
        return ResponseEntity.ok()
                .header("X-Kixima-Documentos", String.valueOf(r.resumo().get("documentos")))
                .header("X-Kixima-Sem-Serie", String.valueOf(r.resumo().get("semSerieCertificada")))
                .header("Content-Disposition", "attachment; filename=\"SAFT-AO-" + periodo.get("de") + "-a-" + periodo.get("ate") + ".xml\"")
                .contentType(new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8))
                .body(r.xml().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/saft/resumo")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN, ADMIN_SISTEMA})
    @RequirePermission(FATURACAO)
    public Map<String, Object> saftResumo(@RequestParam(required = false) String de, @RequestParam(required = false) String ate,
                                          @RequestParam(required = false) String supplierCompanyId) {
        return saftService.gerar(de, ate, resolverEmpresaFornecedora(supplierCompanyId)).resumo();
    }

    @GetMapping("/metricas")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FATURACAO)
    public Map<String, Object> metricas(@RequestParam(required = false) String dias) {
        return metricasService.resumo(dias);
    }
}
