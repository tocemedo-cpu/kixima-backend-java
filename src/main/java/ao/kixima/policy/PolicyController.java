package ao.kixima.policy;

import ao.kixima.common.error.ForbiddenException;
import ao.kixima.policy.dto.ClientPolicyDto;
import ao.kixima.policy.dto.CompanyPoliciesDto;
import ao.kixima.policy.dto.DecidePolicyRequest;
import ao.kixima.policy.dto.PolicyRequest;
import ao.kixima.policy.dto.SupplierPolicyDto;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static ao.kixima.security.AdminArea.APOLICES;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;
import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.FORNECEDOR;
import static org.springframework.http.HttpStatus.CREATED;

/** Espelha policyRoutes.js/policyController.js — todas as rotas exigem autenticação (sem entradas em PublicPaths). */
@RestController
@RequestMapping("/api/policies")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping("/supplier-to-kixima")
    @ResponseStatus(CREATED)
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public SupplierPolicyDto submeterApoliceFornecedor(@RequestBody PolicyRequest body) {
        return policyService.submeterApoliceFornecedor(CurrentUserHolder.get().companyId(), body);
    }

    @PatchMapping("/supplier-to-kixima/{id}/decision")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(APOLICES)
    public SupplierPolicyDto decidirApoliceFornecedor(@PathVariable String id, @RequestBody DecidePolicyRequest body) {
        return policyService.decidirApoliceFornecedor(id, body.approve());
    }

    @PostMapping("/kixima-to-client/{companyId}")
    @ResponseStatus(CREATED)
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(APOLICES)
    public ClientPolicyDto emitirApoliceCliente(@PathVariable String companyId, @RequestBody PolicyRequest body) {
        return policyService.emitirApoliceCliente(companyId, body, CurrentUserHolder.get().id());
    }

    /**
     * Apólices de uma empresa — parte da ficha da empresa. Sem esta guarda,
     * qualquer utilizador autenticado lia as apólices de QUALQUER empresa
     * passando o id na rota (seguradora, nº, cobertura, validade).
     */
    @GetMapping({"/company", "/company/{companyId}"})
    public CompanyPoliciesDto listarApolicesDaEmpresa(@PathVariable(required = false) String companyId) {
        CurrentUser user = CurrentUserHolder.get();
        String alvo = companyId != null ? companyId : user.companyId();
        if (user.role() != ADMIN_SISTEMA && !alvo.equals(user.companyId())) {
            throw new ForbiddenException("Não pode aceder às apólices de outra empresa.");
        }
        return policyService.listarApolicesDaEmpresa(alvo);
    }
}
