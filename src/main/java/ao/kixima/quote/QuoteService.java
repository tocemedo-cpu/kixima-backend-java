package ao.kixima.quote;

import ao.kixima.catalog.Product;
import ao.kixima.catalog.ProductRepository;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.ForbiddenException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.plan.PlanLimit;
import ao.kixima.plan.PlanService;
import ao.kixima.quote.dto.CreateQuoteRequest;
import ao.kixima.quote.dto.QuoteDto;
import ao.kixima.quote.dto.RespondQuoteRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Espelha backend/src/services/quoteService.js — pedidos de cotação (RFQ):
 * o comprador pede preço a um fornecedor; o fornecedor responde com
 * preço/prazo; o comprador pode encerrar.
 */
@Service
public class QuoteService {

    private final QuoteRequestRepository quoteRequestRepository;
    private final QuoteItemRepository quoteItemRepository;
    private final ProductRepository productRepository;
    private final CompanyRepository companyRepository;
    private final PlanService planService;

    @PersistenceContext
    private EntityManager entityManager;

    public QuoteService(QuoteRequestRepository quoteRequestRepository, QuoteItemRepository quoteItemRepository,
                        ProductRepository productRepository, CompanyRepository companyRepository, PlanService planService) {
        this.quoteRequestRepository = quoteRequestRepository;
        this.quoteItemRepository = quoteItemRepository;
        this.productRepository = productRepository;
        this.companyRepository = companyRepository;
        this.planService = planService;
    }

    /** Quantas cotações esta empresa já pediu no mês corrente (UTC) — e se o plano ainda deixa mais uma. */
    private void assertCotacoesDoMes(String buyerCompanyId) {
        Company empresa = companyRepository.findById(buyerCompanyId).orElse(null);
        if (empresa == null || planService.limite(empresa.getPlan(), PlanLimit.COTACOES_POR_MES) == null) return;
        Instant inicioDoMes = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        long usadas = quoteRequestRepository.countByBuyerCompanyIdAndCreatedAtGreaterThanEqual(buyerCompanyId, inicioDoMes);
        planService.assertLimite(empresa, PlanLimit.COTACOES_POR_MES, (int) usadas, "pedidos de cotação por mês");
    }

    @Transactional
    public QuoteDto createRequest(String buyerCompanyId, String createdById, CreateQuoteRequest body) {
        // Cotações por mês: limita a INTENSIDADE de uso, não o catálogo. Cada pedido
        // custa trabalho ao fornecedor do outro lado, por isso é uma medida honesta de
        // quanto a empresa está a usar a plataforma.
        assertCotacoesDoMes(buyerCompanyId);

        if (body.supplierCompanyId().equals(buyerCompanyId)) {
            throw new BusinessRuleException("Não pode pedir cotação à sua própria empresa.");
        }
        if (body.items() == null || body.items().isEmpty()) {
            throw new BusinessRuleException("Adicione pelo menos um produto ao pedido de cotação.");
        }
        List<String> productIds = body.items().stream().map(CreateQuoteRequest.Item::productId).toList();
        List<Product> products = productRepository.findAllById(new HashSet<>(productIds));
        // Prisma `findMany({ id: { in } })` devolve um por id distinto; a comparação de tamanhos é contra a lista pedida.
        if (products.size() != productIds.size() || products.stream().anyMatch(p -> !body.supplierCompanyId().equals(p.getSupplierId()))) {
            throw new BusinessRuleException("Todos os produtos devem pertencer ao fornecedor escolhido.");
        }

        Instant agora = Instant.now();
        QuoteRequest quote = quoteRequestRepository.save(new QuoteRequest(UUID.randomUUID().toString(), buyerCompanyId,
                body.supplierCompanyId(), createdById, body.note() == null || body.note().isBlank() ? null : body.note(), agora));
        List<QuoteItem> items = new ArrayList<>();
        for (CreateQuoteRequest.Item i : body.items()) {
            items.add(quoteItemRepository.save(new QuoteItem(UUID.randomUUID().toString(), quote.getId(), i.productId(), i.quantidadeOuUm())));
        }
        // O Node devolve o registo relido com o INCLUDE; as relações só-leitura (buyerCompany, product…)
        // ficam por carregar em entidades acabadas de persistir — reler garante a mesma forma.
        entityManager.flush();
        entityManager.refresh(quote);
        items.forEach(entityManager::refresh);
        return toDto(quote, items);
    }

    @Transactional(readOnly = true)
    public List<QuoteDto> listForBuyer(String buyerCompanyId, QuoteStatus status) {
        return toDtos(status == null
                ? quoteRequestRepository.findByBuyerCompanyIdOrderByCreatedAtDesc(buyerCompanyId)
                : quoteRequestRepository.findByBuyerCompanyIdAndStatusOrderByCreatedAtDesc(buyerCompanyId, status));
    }

    @Transactional(readOnly = true)
    public List<QuoteDto> listForSupplier(String supplierCompanyId, QuoteStatus status) {
        return toDtos(status == null
                ? quoteRequestRepository.findBySupplierCompanyIdOrderByCreatedAtDesc(supplierCompanyId)
                : quoteRequestRepository.findBySupplierCompanyIdAndStatusOrderByCreatedAtDesc(supplierCompanyId, status));
    }

    @Transactional
    public QuoteDto respond(String id, String supplierCompanyId, RespondQuoteRequest body) {
        QuoteRequest quote = quoteRequestRepository.findById(id).orElseThrow(() -> new NotFoundException("Pedido de cotação"));
        if (!quote.getSupplierCompanyId().equals(supplierCompanyId)) throw new ForbiddenException("Só o fornecedor do pedido pode responder.");
        if (quote.getStatus() == QuoteStatus.FECHADA) throw new BusinessRuleException("Este pedido já foi encerrado.");
        quote.responder(body.price(), body.leadDays(), body.note() == null || body.note().isBlank() ? null : body.note(), Instant.now());
        return toDto(quote, itensDe(quote));
    }

    @Transactional
    public QuoteDto close(String id, String buyerCompanyId) {
        QuoteRequest quote = quoteRequestRepository.findById(id).orElseThrow(() -> new NotFoundException("Pedido de cotação"));
        if (!quote.getBuyerCompanyId().equals(buyerCompanyId)) throw new ForbiddenException("Só o comprador do pedido pode encerrá-lo.");
        quote.encerrar();
        return toDto(quote, itensDe(quote));
    }

    private List<QuoteItem> itensDe(QuoteRequest quote) {
        return quoteItemRepository.findByQuoteRequestIdIn(List.of(quote.getId()));
    }

    private QuoteDto toDto(QuoteRequest quote, List<QuoteItem> items) {
        return QuoteDto.de(quote, items);
    }

    private List<QuoteDto> toDtos(List<QuoteRequest> quotes) {
        if (quotes.isEmpty()) return List.of();
        Map<String, List<QuoteItem>> porPedido = quoteItemRepository
                .findByQuoteRequestIdIn(quotes.stream().map(QuoteRequest::getId).toList())
                .stream().collect(Collectors.groupingBy(QuoteItem::getQuoteRequestId));
        return quotes.stream().map(q -> toDto(q, porPedido.getOrDefault(q.getId(), List.of()))).toList();
    }
}
