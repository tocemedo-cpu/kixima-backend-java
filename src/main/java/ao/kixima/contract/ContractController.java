package ao.kixima.contract;

import ao.kixima.contract.dto.ContractDto;
import ao.kixima.contract.dto.CreateContractRequest;
import ao.kixima.invoice.dto.InvoiceDto;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.PersonaRole;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static ao.kixima.security.AdminArea.CADASTRO;
import static ao.kixima.security.AdminArea.FINANCEIRO;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;
import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static org.springframework.http.HttpStatus.CREATED;

/** Espelha contractRoutes.js/contractController.js — todas as rotas exigem autenticação. */
@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @PostMapping
    @ResponseStatus(CREATED)
    @RequireRole({ADMIN_SISTEMA, COMPANY_ADMIN})
    @RequirePermission(CADASTRO)
    public ContractDto create(@Valid @RequestBody CreateContractRequest body) {
        return contractService.createContract(body, CurrentUserHolder.get());
    }

    /** O Admin do Sistema não tem empresa própria — vê todos os contratos. */
    @GetMapping
    public List<ContractDto> listMine() {
        CurrentUser user = CurrentUserHolder.get();
        return user.role() == PersonaRole.ADMIN_SISTEMA
                ? contractService.listAllContracts()
                : contractService.listContractsForCompany(user.companyId());
    }

    @GetMapping("/{id}")
    public ContractDto getOne(@PathVariable String id) {
        return contractService.getContract(id, CurrentUserHolder.get());
    }

    @PostMapping("/{id}/consolidate-billing")
    @ResponseStatus(CREATED)
    @RequireRole({ADMIN_SISTEMA, PersonaRole.FINANCEIRO, COMPANY_ADMIN})
    @RequirePermission(FINANCEIRO)
    public InvoiceDto consolidateBilling(@PathVariable String id) {
        return InvoiceDto.de(contractService.consolidateContractBilling(id, CurrentUserHolder.get()), false);
    }
}
