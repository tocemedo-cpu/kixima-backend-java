package ao.kixima.supplierdev;

import ao.kixima.common.error.ErrosDeCampos;
import ao.kixima.common.error.ValidationException;
import ao.kixima.plan.PlanService;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import ao.kixima.supplierdev.dto.ApproveSupplierDevRequest;
import ao.kixima.supplierdev.dto.CreateSupplierDevRequest;
import ao.kixima.supplierdev.dto.SupplierDevCreatedDto;
import ao.kixima.supplierdev.dto.SupplierDevListResponse;
import ao.kixima.supplierdev.dto.SupplierDevPublicDto;
import ao.kixima.supplierdev.dto.SupplierDevRequestDto;
import ao.kixima.supplierdev.dto.UpdateSupplierDevRequest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static ao.kixima.security.AdminArea.SUPORTE;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;
import static org.springframework.http.HttpStatus.CREATED;

/**
 * Espelha backend/src/routes/supplierDevRoutes.js — a CANDIDATURA é
 * pública (entra pela página de login e pela home, pode vir de empresa
 * ainda sem conta); a GESTÃO é do Admin do Sistema, área Suporte.
 *
 * NÃO PORTADO: o limitador de 10 candidaturas/15min
 * ({@code publicLimiter}) — precisa de Bucket4j, ainda não é dependência
 * do projeto (mesma lacuna documentada em SupportController/
 * ConversationController para os seus próprios limitadores).
 */
@RestController
@RequestMapping("/api/supplier-development")
public class SupplierDevController {

    private static final List<String> TRACKS = List.of("BUROCRACIA", "PARCERIA", "AMBOS");

    private final SupplierDevService supplierDevService;
    private final PlanService planService;

    public SupplierDevController(SupplierDevService supplierDevService, PlanService planService) {
        this.supplierDevService = supplierDevService;
        this.planService = planService;
    }

    // --- Público -------------------------------------------------------

    @GetMapping("/fee")
    public PlanService.SupplierDevAccessFee taxaDeAcesso() {
        return planService.supplierDevAccessFee();
    }

    @PostMapping("/requests")
    @ResponseStatus(CREATED)
    public SupplierDevCreatedDto candidatar(@RequestBody CreateSupplierDevRequest body) {
        Integer employees = validarCandidatura(body);
        CurrentUser user = CurrentUserHolder.get();
        // `track: data.track || 'AMBOS'` no supplierDevService.js.
        SupplierDevTrack track = body.track() == null || body.track().isBlank()
                ? SupplierDevTrack.AMBOS : SupplierDevTrack.valueOf(body.track());
        return supplierDevService.criar(user == null ? null : user.companyId(), body.companyName().trim(),
                body.taxId(), body.contactName().trim(), body.contactEmail(), body.contactPhone(), body.province(),
                body.sector(), employees, track, body.needs());
    }

    /**
     * Espelha {@code validate(supplierDevSchema)}: todas as restrições do zod,
     * pela ordem do schema, reportadas de uma vez em {@code fieldErrors} com os
     * textos por omissão do zod. Devolve {@code employees} já coagido
     * ({@code z.coerce.number()}: "24" → 24, {@code null} → 0), ou null se ausente.
     */
    private static Integer validarCandidatura(CreateSupplierDevRequest body) {
        ErrosDeCampos erros = new ErrosDeCampos();
        // z.string().min(2, msg): comprimento sem trim — "  " tem 2 caracteres e passa, como no Node.
        if (body.companyName() == null) erros.adicionar("companyName", ErrosDeCampos.REQUIRED);
        else if (body.companyName().length() < 2) erros.adicionar("companyName", "Indique o nome da empresa.");
        erros.textoOpcionalAte("taxId", body.taxId(), 40);
        if (body.contactName() == null) erros.adicionar("contactName", ErrosDeCampos.REQUIRED);
        else if (body.contactName().length() < 2) erros.adicionar("contactName", "Indique o nome do contacto.");
        if (body.contactEmail() == null) erros.adicionar("contactEmail", ErrosDeCampos.REQUIRED);
        else if (!ErrosDeCampos.EMAIL.matcher(body.contactEmail()).matches()) erros.adicionar("contactEmail", "Indique um email válido.");
        erros.textoOpcionalAte("contactPhone", body.contactPhone(), 40);
        erros.textoOpcionalAte("province", body.province(), 60);
        erros.textoOpcionalAte("sector", body.sector(), 120);
        Integer employees = coagirEmployees(body.employees(), erros);
        erros.enumOpcional("track", body.track(), TRACKS);
        erros.textoOpcionalAte("needs", body.needs(), 2000);
        // z.literal(true) com errorMap próprio: qualquer coisa que não seja `true` (ausente incluído) dá esta frase.
        if (body.feeAccepted() == null || !body.feeAccepted().isBoolean() || !body.feeAccepted().asBoolean()) {
            erros.adicionar("feeAccepted", "Confirme que aceita a taxa de acesso cobrada na submissão.");
        }
        erros.lancarSeHouver();
        return employees;
    }

    /**
     * {@code z.coerce.number().int().nonnegative().optional()} — `Number(v)` do JS
     * e depois as duas verificações, que o zod acumula (não pára na primeira).
     * Só {@code undefined} escapa ao {@code optional()}: {@code null} vira 0.
     */
    private static Integer coagirEmployees(JsonNode v, ErrosDeCampos erros) {
        if (v == null) return null;
        double n;
        if (v.isNull()) n = 0;
        else if (v.isNumber()) n = v.doubleValue();
        else if (v.isBoolean()) n = v.asBoolean() ? 1 : 0;
        else if (v.isTextual()) n = numeroJs(v.asText());
        else n = Double.NaN;
        if (Double.isNaN(n)) {
            erros.adicionar("employees", "Expected number, received nan");
            return null;
        }
        boolean inteiro = !Double.isInfinite(n) && n == Math.rint(n);
        if (!inteiro) erros.adicionar("employees", "Expected integer, received float");
        if (n < 0) erros.adicionar("employees", "Number must be greater than or equal to 0");
        return inteiro && n >= 0 ? (int) n : null;
    }

    /** `Number(string)` do JS nos casos que interessam: vazio → 0, número → número, resto → NaN. */
    private static double numeroJs(String s) {
        String t = s.strip();
        if (t.isEmpty()) return 0;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    @GetMapping("/requests/{reference}/track")
    public SupplierDevPublicDto acompanhar(@PathVariable String reference) {
        return supplierDevService.acompanharPorReferencia(reference);
    }

    // --- Admin do Sistema ------------------------------------------------

    @GetMapping("/requests")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public SupplierDevListResponse listar(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer limit,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String track,
                                           @RequestParam(required = false) String q) {
        SupplierDevStatus s = status == null || status.isBlank() ? null : SupplierDevStatus.valueOf(status);
        SupplierDevTrack t = track == null || track.isBlank() ? null : trackValido(track);
        return supplierDevService.listar(page, limit, s, t, q);
    }

    @PatchMapping("/requests/{id}")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public SupplierDevRequestDto atualizar(@PathVariable String id, @RequestBody UpdateSupplierDevRequest body) {
        return supplierDevService.atualizar(id, body, CurrentUserHolder.get());
    }

    @PatchMapping("/requests/{id}/approve")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(SUPORTE)
    public SupplierDevRequestDto aprovar(@PathVariable String id, @RequestBody ApproveSupplierDevRequest body, HttpServletRequest req) {
        String baseUrl = req.getScheme() + "://" + (req.getHeader("host") != null ? req.getHeader("host") : req.getServerName());
        return supplierDevService.aprovar(id, body, CurrentUserHolder.get(), baseUrl);
    }

    private SupplierDevTrack trackValido(String track) {
        try {
            return SupplierDevTrack.valueOf(track);
        } catch (Exception e) {
            throw new ValidationException("Percurso inválido — use BUROCRACIA, PARCERIA ou AMBOS.");
        }
    }
}
