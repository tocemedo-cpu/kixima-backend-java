package ao.kixima.erp;

import ao.kixima.common.error.ValidationException;
import ao.kixima.erp.dto.ErpConfigAuditDto;
import ao.kixima.erp.dto.ErpConfigDto;
import ao.kixima.erp.dto.ErpTestResultDto;
import ao.kixima.erp.dto.SetErpConfigRequest;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static ao.kixima.security.AdminArea.CADASTRO;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;

/**
 * Espelha o troço "Configuração ERP" de backend/src/routes/companyRoutes.js
 * + backend/src/controllers/companyController.js — só o Administrador do
 * Sistema KIXIMA, área Cadastro.
 */
@RestController
@RequestMapping("/api/companies")
public class ErpConfigController {

    private final ErpConfigService erpConfigService;

    public ErpConfigController(ErpConfigService erpConfigService) {
        this.erpConfigService = erpConfigService;
    }

    @GetMapping("/{id}/erp-config")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(CADASTRO)
    public ErpConfigDto getErpConfig(@PathVariable String id) {
        return erpConfigService.getConfig(id);
    }

    @PutMapping("/{id}/erp-config")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(CADASTRO)
    public ErpConfigDto setErpConfig(@PathVariable String id, @RequestBody SetErpConfigRequest body) {
        if (body.erp() == null || body.erp().isBlank()) throw new ValidationException("Indique o sistema ERP.");
        return erpConfigService.setConfig(id, body.erp(), body.config(), CurrentUserHolder.get());
    }

    @PostMapping("/{id}/erp-config/test")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(CADASTRO)
    public ErpTestResultDto testErpConnection(@PathVariable String id) {
        return erpConfigService.testConnection(id, CurrentUserHolder.get());
    }

    @GetMapping("/{id}/erp-config/audits")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(CADASTRO)
    public List<ErpConfigAuditDto> listErpAudits(@PathVariable String id) {
        return erpConfigService.listAudits(id);
    }
}
