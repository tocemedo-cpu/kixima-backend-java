package ao.kixima.company;

import ao.kixima.common.error.ForbiddenException;
import ao.kixima.payment.PlatformFeeService;
import ao.kixima.payment.dto.PlatformFeeStatementDto;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.PersonaRole;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static ao.kixima.security.AdminArea.FINANCEIRO;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;
import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.FORNECEDOR;

/** Espelha `GET /:id/platform-fees` de companyRoutes.js — o extrato de taxas da própria empresa (ou de qualquer uma, para o Admin). */
@RestController
@RequestMapping("/api/companies")
public class CompanyPlatformFeeController {

    private final PlatformFeeService platformFeeService;

    public CompanyPlatformFeeController(PlatformFeeService platformFeeService) {
        this.platformFeeService = platformFeeService;
    }

    /** `assertOwnCompany` de companyController.js. */
    static void assertOwnCompany(CurrentUser user, String companyId) {
        if (user.role() != PersonaRole.ADMIN_SISTEMA && !companyId.equals(user.companyId())) {
            throw new ForbiddenException("Não pode aceder aos dados de outra empresa.");
        }
    }

    @GetMapping("/{id}/platform-fees")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN, PersonaRole.FINANCEIRO, ADMIN_SISTEMA})
    @RequirePermission(FINANCEIRO)
    public PlatformFeeStatementDto statement(@PathVariable String id) {
        assertOwnCompany(CurrentUserHolder.get(), id);
        return platformFeeService.statementFor(id);
    }
}
