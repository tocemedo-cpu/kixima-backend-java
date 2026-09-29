package ao.kixima.quote;

import ao.kixima.quote.dto.CreateQuoteRequest;
import ao.kixima.quote.dto.QuoteDto;
import ao.kixima.quote.dto.RespondQuoteRequest;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.PersonaRole;
import ao.kixima.security.RequireRole;
import jakarta.validation.Valid;
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

import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.COMPRADOR;
import static ao.kixima.security.PersonaRole.FORNECEDOR;
import static org.springframework.http.HttpStatus.CREATED;

/** Espelha quoteRoutes.js — todas as rotas exigem autenticação. */
@RestController
@RequestMapping("/api/quotes")
public class QuoteController {

    private final QuoteService quoteService;

    public QuoteController(QuoteService quoteService) {
        this.quoteService = quoteService;
    }

    /** Lista conforme a persona: comprador vê os seus pedidos, fornecedor os que recebeu. */
    @GetMapping
    public List<QuoteDto> listar(@RequestParam(required = false) QuoteStatus status) {
        CurrentUser user = CurrentUserHolder.get();
        if (user.role() == PersonaRole.FORNECEDOR || user.role() == PersonaRole.COMPANY_ADMIN) {
            return quoteService.listForSupplier(user.companyId(), status);
        }
        return quoteService.listForBuyer(user.companyId(), status);
    }

    /** Comprador cria o pedido de cotação. */
    @PostMapping
    @ResponseStatus(CREATED)
    @RequireRole({COMPRADOR})
    public QuoteDto criar(@Valid @RequestBody CreateQuoteRequest body) {
        CurrentUser user = CurrentUserHolder.get();
        return quoteService.createRequest(user.companyId(), user.id(), body);
    }

    /** Fornecedor responde com preço/prazo. */
    @PatchMapping("/{id}/respond")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN})
    public QuoteDto responder(@PathVariable String id, @Valid @RequestBody RespondQuoteRequest body) {
        return quoteService.respond(id, CurrentUserHolder.get().companyId(), body);
    }

    /** Comprador encerra o pedido. */
    @PatchMapping("/{id}/close")
    @RequireRole({COMPRADOR})
    public QuoteDto encerrar(@PathVariable String id) {
        return quoteService.close(id, CurrentUserHolder.get().companyId());
    }
}
