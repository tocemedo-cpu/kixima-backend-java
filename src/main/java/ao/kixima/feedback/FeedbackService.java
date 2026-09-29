package ao.kixima.feedback;

import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.common.pagination.PaginaResposta;
import ao.kixima.common.pagination.Paginacao;
import ao.kixima.company.Company;
import ao.kixima.feedback.dto.CreateFeedbackRequest;
import ao.kixima.feedback.dto.FeedbackCreatedDto;
import ao.kixima.feedback.dto.FeedbackDto;
import ao.kixima.feedback.dto.FeedbackNameRef;
import ao.kixima.feedback.dto.FeedbackOptionDto;
import ao.kixima.feedback.dto.FeedbackOptionsResponse;
import ao.kixima.feedback.dto.PublicFeedbackResponse;
import ao.kixima.catalog.ProductKind;
import ao.kixima.payment.Payment;
import ao.kixima.payment.PaymentRepository;
import ao.kixima.payment.PaymentStatus;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.po.PurchaseOrderItem;
import ao.kixima.po.PurchaseOrderItemRepository;
import ao.kixima.po.PurchaseOrderRepository;
import ao.kixima.support.SupportTicket;
import ao.kixima.support.SupportTicketRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/feedbackService.js — avaliações da homepage
 * corporativa ("Avaliações Verificadas"). Só quem tem sessão e empresa na
 * KIXIMA avalia; o alvo (fornecedor, produto, pedido, entrega, atendimento)
 * tem de ser algo que a empresa do autor realmente viveu (ver
 * {@link #resolverAlvo}) — nunca aceite de olhos fechados a partir do
 * cliente. "Experiência geral" é a única categoria sem alvo.
 *
 * A categoria PAGAMENTO verifica-se contra {@link Payment} (PROCESSADO, de
 * uma PO em que a empresa é parte) — portado com o domínio de pagamentos
 * (grupo A das lacunas pós-M6).
 */
@Service
public class FeedbackService {

    private static final int MAX_OPCOES = 30;
    private static final int MAX_MENSAGEM = 700;

    private final FeedbackRepository feedbackRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final PaymentRepository paymentRepository;

    public FeedbackService(FeedbackRepository feedbackRepository, PurchaseOrderRepository purchaseOrderRepository,
                            PurchaseOrderItemRepository purchaseOrderItemRepository, SupportTicketRepository supportTicketRepository,
                            PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
        this.feedbackRepository = feedbackRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderItemRepository = purchaseOrderItemRepository;
        this.supportTicketRepository = supportTicketRepository;
    }

    /** Opções reais para o dropdown "sobre o que é esta avaliação" — construídas do histórico real da empresa. */
    @Transactional(readOnly = true)
    public FeedbackOptionsResponse opcoes(String companyId, String userId) {
        if (companyId == null) {
            return new FeedbackOptionsResponse(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        List<PurchaseOrder> pedidos = purchaseOrderRepository.findByCompanyIdOrderByCreatedAtDesc(companyId, Pageable.ofSize(100));
        List<SupportTicket> tickets = supportTicketRepository.findByCompanyIdOrUserIdOrderByCreatedAtDesc(companyId, userId, Pageable.ofSize(MAX_OPCOES));

        Map<String, FeedbackOptionDto> fornecedores = new LinkedHashMap<>();
        Map<String, FeedbackOptionDto> produtos = new LinkedHashMap<>();
        Map<String, FeedbackOptionDto> servicos = new LinkedHashMap<>();
        List<FeedbackOptionDto> pedidosOpts = new java.util.ArrayList<>();
        List<FeedbackOptionDto> entregas = new java.util.ArrayList<>();
        List<FeedbackOptionDto> pagamentos = new java.util.ArrayList<>();

        // `po.invoice.payment` do Node — um SELECT só para os pagamentos das 100 POs.
        Map<String, Payment> pagamentoPorFatura = new LinkedHashMap<>();
        List<String> invoiceIds = pedidos.stream().map(PurchaseOrder::getInvoice).filter(java.util.Objects::nonNull)
                .map(ao.kixima.invoice.Invoice::getId).toList();
        if (!invoiceIds.isEmpty()) {
            for (Payment p : paymentRepository.findByInvoiceIdIn(invoiceIds)) pagamentoPorFatura.put(p.getInvoiceId(), p);
        }

        for (PurchaseOrder po : pedidos) {
            boolean souComprador = po.getBuyerCompanyId().equals(companyId);
            Company contraparte = souComprador ? po.getSupplierCompany() : po.getBuyerCompany();
            if (contraparte != null) fornecedores.putIfAbsent(contraparte.getId(), new FeedbackOptionDto(contraparte.getId(), contraparte.getName()));
            for (PurchaseOrderItem item : po.getItems()) {
                var produto = item.getProduct();
                if (produto == null) continue;
                Map<String, FeedbackOptionDto> alvo = produto.getKind() == ProductKind.SERVICO ? servicos : produtos;
                alvo.putIfAbsent(produto.getId(), new FeedbackOptionDto(produto.getId(), produto.getName()));
            }
            String rotulo = po.getReference() + " — " + (contraparte != null ? contraparte.getName() : "");
            pedidosOpts.add(new FeedbackOptionDto(po.getId(), rotulo));
            if (po.getDeliveredAt() != null || po.getReceivedAt() != null) {
                entregas.add(new FeedbackOptionDto(po.getId(), rotulo));
            }
            Payment pagamento = po.getInvoice() == null ? null : pagamentoPorFatura.get(po.getInvoice().getId());
            if (pagamento != null && pagamento.getStatus() == PaymentStatus.PROCESSADO) {
                pagamentos.add(new FeedbackOptionDto(pagamento.getId(), pagamento.getReference() + " — " + po.getReference()));
            }
        }

        List<FeedbackOptionDto> atendimento = tickets.stream()
                .map(t -> new FeedbackOptionDto(t.getId(), t.getReference() + " — " + t.getSubject()))
                .toList();

        return new FeedbackOptionsResponse(
                limitar(fornecedores.values()), limitar(produtos.values()), limitar(servicos.values()),
                limitar(pedidosOpts), limitar(entregas), limitar(pagamentos), limitar(atendimento));
    }

    private List<FeedbackOptionDto> limitar(java.util.Collection<FeedbackOptionDto> valores) {
        return valores.stream().limit(MAX_OPCOES).toList();
    }

    /**
     * Confirma que o alvo escolhido pertence mesmo ao histórico da empresa
     * do autor e devolve o rótulo a guardar como snapshot — aceitar um id
     * qualquer sem verificar tornaria o selo "Verificado" uma mentira.
     */
    private String[] resolverAlvo(FeedbackCategoria categoria, String targetId, String companyId, String userId) {
        if (categoria == FeedbackCategoria.EXPERIENCIA_GERAL) return new String[]{null, null};
        if (targetId == null || targetId.isBlank()) throw new ValidationException("Escolha a que se refere esta avaliação.");

        ValidationException naoEncontrado = new ValidationException("Não encontrámos esse registo no histórico da sua empresa.");

        switch (categoria) {
            case FORNECEDOR -> {
                List<PurchaseOrder> pos = purchaseOrderRepository.findEntreEmpresas(companyId, targetId, Pageable.ofSize(1));
                if (pos.isEmpty()) throw naoEncontrado;
                PurchaseOrder po = pos.get(0);
                Company contraparte = po.getBuyerCompanyId().equals(companyId) ? po.getSupplierCompany() : po.getBuyerCompany();
                return new String[]{targetId, contraparte.getName()};
            }
            case PRODUTO, SERVICO -> {
                List<PurchaseOrderItem> itens = purchaseOrderItemRepository.findByProductIdEComEmpresa(targetId, companyId, Pageable.ofSize(1));
                if (itens.isEmpty() || itens.get(0).getProduct().getKind() != ProductKind.valueOf(categoria.name())) throw naoEncontrado;
                return new String[]{targetId, itens.get(0).getProduct().getName()};
            }
            case PEDIDO, ENTREGA -> {
                PurchaseOrder po = purchaseOrderRepository.findById(targetId).orElseThrow(() -> naoEncontrado);
                boolean pertence = po.getBuyerCompanyId().equals(companyId) || po.getSupplierCompanyId().equals(companyId);
                if (!pertence) throw naoEncontrado;
                if (categoria == FeedbackCategoria.ENTREGA && po.getDeliveredAt() == null && po.getReceivedAt() == null) throw naoEncontrado;
                Company contraparte = po.getBuyerCompanyId().equals(companyId) ? po.getSupplierCompany() : po.getBuyerCompany();
                return new String[]{targetId, po.getReference() + " — " + (contraparte != null ? contraparte.getName() : "")};
            }
            case PAGAMENTO -> {
                Payment pagamento = paymentRepository.findByIdComFatura(targetId).orElseThrow(() -> naoEncontrado);
                PurchaseOrder po = pagamento.getInvoice().getPurchaseOrder();
                boolean daEmpresa = po != null && (po.getBuyerCompanyId().equals(companyId) || po.getSupplierCompanyId().equals(companyId));
                if (pagamento.getStatus() != PaymentStatus.PROCESSADO || !daEmpresa) throw naoEncontrado;
                return new String[]{targetId, pagamento.getReference() + " — " + po.getReference()};
            }
            case ATENDIMENTO -> {
                SupportTicket ticket = supportTicketRepository.findById(targetId).orElseThrow(() -> naoEncontrado);
                boolean pertence = (ticket.getCompanyId() != null && ticket.getCompanyId().equals(companyId)) || ticket.getUserId().equals(userId);
                if (!pertence) throw naoEncontrado;
                return new String[]{targetId, ticket.getReference() + " — " + ticket.getSubject()};
            }
            default -> throw naoEncontrado;
        }
    }

    /** Submissão pelo utilizador autenticado — nome e empresa vêm sempre da sessão. */
    @Transactional
    public FeedbackCreatedDto criar(String userId, String companyId, String categoriaBruta, String targetId, Integer rating, String message) {
        if (companyId == null) throw new ValidationException("É necessário pertencer a uma empresa para enviar uma avaliação.");
        FeedbackCategoria categoria = categoriaValida(categoriaBruta);

        if (rating == null || rating < 1 || rating > 5) throw new ValidationException("Selecione uma classificação entre 1 e 5.");

        String mensagemLimpa = message == null ? "" : message.trim();
        if (mensagemLimpa.length() > MAX_MENSAGEM) mensagemLimpa = mensagemLimpa.substring(0, MAX_MENSAGEM);
        if (mensagemLimpa.isEmpty()) throw new ValidationException("Escreva um comentário.");

        String[] alvo = resolverAlvo(categoria, targetId, companyId, userId);

        Feedback criado = new Feedback(UUID.randomUUID().toString(), userId, companyId, categoria, alvo[0], alvo[1],
                rating, mensagemLimpa, Instant.now());
        feedbackRepository.save(criado);
        return new FeedbackCreatedDto(true, criado.getId());
    }

    private FeedbackCategoria categoriaValida(String categoriaBruta) {
        try {
            return FeedbackCategoria.valueOf(categoriaBruta);
        } catch (Exception e) {
            throw new ValidationException("Categoria inválida. Escolha uma de: "
                    + String.join(", ", java.util.Arrays.stream(FeedbackCategoria.values()).map(Enum::name).toList()) + ".");
        }
    }

    @Transactional(readOnly = true)
    public List<FeedbackDto> minhas(String userId) {
        return feedbackRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toDto).toList();
    }

    /** A parede pública: as aprovadas mais recentes + a média de TODAS as aprovadas. */
    @Transactional(readOnly = true)
    public PublicFeedbackResponse publicar() {
        List<FeedbackDto> recentes = feedbackRepository.findTopApprovedOrderByCreatedAtDesc(Pageable.ofSize(6)).stream()
                .map(f -> toDto(f).semEstadoDeModeracao()).toList();
        long total = feedbackRepository.countByApproved(true);
        Double media = feedbackRepository.mediaAprovadas();
        double average = total > 0 && media != null ? Math.round(media * 10) / 10.0 : 0;
        return new PublicFeedbackResponse(recentes, total, average);
    }

    /** Fila de moderação do Admin do Sistema — tudo, aprovado ou não, mais recente primeiro. */
    @Transactional(readOnly = true)
    public PaginaResposta<FeedbackDto> listarAdmin(Integer page, Integer limit, String status) {
        Pageable pageable = Paginacao.parametros(page, limit);
        Page<Feedback> pagina = "pendente".equals(status) ? feedbackRepository.findByApprovedOrderByCreatedAtDesc(false, pageable)
                : "aprovado".equals(status) ? feedbackRepository.findByApprovedOrderByCreatedAtDesc(true, pageable)
                : feedbackRepository.findAllByOrderByCreatedAtDesc(pageable);
        return Paginacao.envelope(pagina.map(this::toDto));
    }

    @Transactional
    public FeedbackDto aprovar(String id) {
        Feedback feedback = feedbackRepository.findById(id).orElseThrow(() -> new NotFoundException("Avaliação"));
        feedback.setApproved(true);
        return toDto(feedback);
    }

    @Transactional
    public void remover(String id) {
        Feedback feedback = feedbackRepository.findById(id).orElseThrow(() -> new NotFoundException("Avaliação"));
        feedbackRepository.delete(feedback);
    }

    private FeedbackDto toDto(Feedback f) {
        return new FeedbackDto(f.getId(),
                new FeedbackNameRef(f.getUser() == null ? null : f.getUser().getName()),
                new FeedbackNameRef(f.getCompany() == null ? null : f.getCompany().getName()),
                f.getCategoria().name(), f.getTargetLabel(), f.getRating(), f.getMessage(), f.isVerified(),
                f.isApproved(), f.getCreatedAt());
    }
}
