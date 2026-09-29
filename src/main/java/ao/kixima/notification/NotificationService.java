package ao.kixima.notification;

import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.invoice.Invoice;
import ao.kixima.notification.dto.NotificationDto;
import ao.kixima.payment.Payment;
import ao.kixima.po.PoStatus;
import ao.kixima.realtime.RealtimeService;
import ao.kixima.po.PurchaseOrder;
import ao.kixima.security.PersonaRole;
import ao.kixima.supplierdev.SupplierDevRequest;
import ao.kixima.user.User;
import ao.kixima.user.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Espelha backend/src/services/notificationService.js — implementa a
 * tabela "Regras de notificação" (secção 6 da especificação). Cada evento
 * de negócio chama um método aqui — mantém "quem é avisado, por que
 * canal" num único sítio.
 *
 * DIVERGÊNCIA DELIBERADA face ao Node: lá, os eventos são chamados DEPOIS
 * do {@code prisma.$transaction(...)} fechar (o negócio já está comitado
 * antes de notificar). Aqui, PoService chama estes métodos DENTRO da
 * própria transacção Spring — porque {@link EmailDispatchService#dispatch}
 * nunca lança (falha de envio fica só no log, mesmo princípio do Node) e a
 * escrita da {@link Notification} é uma simples INSERT na mesma base, sem
 * chamada de rede a um terceiro (ao contrário da submissão AGT, essa sim
 * feita depois do commit — ver PoService.acceptPurchaseOrder). Isto torna
 * "PO aprovada" + "notificação criada" atómicos, o que é estritamente mais
 * seguro; a única divergência real é o caso raro de a própria escrita da
 * notificação falhar por erro de base de dados, que aqui reverteria também
 * a operação de negócio, e no Node não.
 *
 * Push em tempo real: {@code realtimeService.emitToUser} está portado
 * (M6, STOMP em {@link ao.kixima.realtime.RealtimeService}); a notificação
 * gravada continua a ser a fonte da verdade, o push é só o aviso.
 *
 * Todos os métodos de domínio do Node têm hoje chamador em Java (subscrição,
 * ERP, Supplier Development, nota de crédito, apólices, cadastro de empresa
 * entraram no M6.5) — ver {@link NotificationType}, que é o inventário.
 */
@Service
public class NotificationService {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final EmailDispatchService emailDispatchService;
    private final RealtimeService realtimeService;

    public NotificationService(NotificationRepository notificationRepository, UserRepository userRepository,
                                CompanyRepository companyRepository, EmailDispatchService emailDispatchService,
                                RealtimeService realtimeService) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.emailDispatchService = emailDispatchService;
        this.realtimeService = realtimeService;
    }

    public Notification notifyUser(String userId, NotificationType type, String title, String message,
                                    NotificationChannel channel, String relatedEntityType, String relatedEntityId,
                                    String emailTo) {
        return notifyUser(userId, type, title, message, channel, relatedEntityType, relatedEntityId, emailTo, null);
    }

    public Notification notifyUser(String userId, NotificationType type, String title, String message,
                                    NotificationChannel channel, String relatedEntityType, String relatedEntityId,
                                    String emailTo, String locale) {
        Notification notification = new Notification(UUID.randomUUID().toString(), userId, null, type, channel,
                title, message, relatedEntityType, relatedEntityId, Instant.now());
        notificationRepository.save(notification);

        if (channel == NotificationChannel.IN_APP_EMAIL && emailTo != null && !emailTo.isBlank()) {
            // O EMAIL sai na língua do destinatário; a notificação in-app fica em
            // português porque a interface a traduz do lado do cliente.
            emailDispatchService.dispatch(emailTo, EmailI18n.t(title, locale), EmailI18n.t(message, locale));
        }

        // Sem efeito se ninguém estiver ligado — a notificação já ficou gravada acima de qualquer
        // forma; isto só evita que a interface precise de recarregar a página para a ver.
        realtimeService.emitToUser(userId, "notification:new", NotificationDto.de(notification));
        return notification;
    }

    public List<Notification> notifyUsersByRole(String companyId, List<PersonaRole> roles, NotificationType type,
                                                  String title, String message, NotificationChannel channel,
                                                  String relatedEntityType, String relatedEntityId) {
        List<User> users = userRepository.findByCompanyIdAndRoleInAndActiveTrue(companyId, roles);
        return users.stream()
                .map(u -> notifyUser(u.getId(), type, title, message, channel, relatedEntityType, relatedEntityId, u.getEmail(), u.getLocale()))
                .toList();
    }

    /** `notifyUsersByRole({ roles })` SEM companyId — os utilizadores internos da KIXIMA (ADMIN_SISTEMA, sem empresa). */
    public List<Notification> notifyPlatformRole(PersonaRole role, NotificationType type, String title, String message,
                                                 String relatedEntityType, String relatedEntityId) {
        return userRepository.findByRoleAndActiveTrue(role).stream()
                .map(u -> notifyUser(u.getId(), type, title, message, NotificationChannel.IN_APP_EMAIL, relatedEntityType, relatedEntityId, u.getEmail(), u.getLocale()))
                .toList();
    }

    public Notification notifyCompanyContact(String companyId, NotificationType type, String title, String message) {
        Company company = companyRepository.findById(companyId).orElse(null);
        if (company == null) return null;

        Notification notification = new Notification(UUID.randomUUID().toString(), null, companyId, type,
                NotificationChannel.EMAIL, title, message, null, null, Instant.now());
        notificationRepository.save(notification);
        emailDispatchService.dispatch(company.getContactEmail(), title, message);
        return notification;
    }

    // --- Eventos de negócio (mapeados 1:1 com a secção 6 da especificação) -----

    public void poAguardaAprovacao(PurchaseOrder po) {
        notifyUsersByRole(po.getBuyerCompanyId(), List.of(PersonaRole.COMPANY_ADMIN), NotificationType.PO_AGUARDA_APROVACAO,
                "Nova PO aguarda aprovação", "A ordem de compra " + po.getReference() + " aguarda a sua aprovação.",
                NotificationChannel.IN_APP, "PurchaseOrder", po.getId());
    }

    public void poAprovadaOuRejeitada(PurchaseOrder po) {
        boolean aprovada = po.getStatus() == PoStatus.APROVADA;
        notifyUser(po.getCreatedById(), aprovada ? NotificationType.PO_APROVADA : NotificationType.PO_REJEITADA,
                aprovada ? "PO aprovada" : "PO rejeitada",
                "A ordem de compra " + po.getReference() + " foi " + (aprovada ? "aprovada" : "rejeitada") + ".",
                NotificationChannel.IN_APP, "PurchaseOrder", po.getId(), null);
    }

    public void poRecebidaPeloFornecedor(PurchaseOrder po) {
        notifyUsersByRole(po.getSupplierCompanyId(), List.of(PersonaRole.FORNECEDOR, PersonaRole.COMPANY_ADMIN),
                NotificationType.PO_RECEBIDA_FORNECEDOR, "Nova ordem de compra recebida",
                "Recebeu a ordem de compra " + po.getReference() + ". Reveja e aceite ou recuse.",
                NotificationChannel.IN_APP_EMAIL, "PurchaseOrder", po.getId());
    }

    public void poRecusadaPeloFornecedor(PurchaseOrder po) {
        notifyUsersByRole(po.getBuyerCompanyId(), List.of(PersonaRole.COMPRADOR, PersonaRole.COMPANY_ADMIN),
                NotificationType.PO_RECUSADA_FORNECEDOR, "PO recusada pelo fornecedor",
                "A ordem de compra " + po.getReference() + " foi recusada pelo fornecedor. Motivo: " + po.getRefusalReason(),
                NotificationChannel.IN_APP_EMAIL, "PurchaseOrder", po.getId());
    }

    public void faturaGerada(Invoice invoice, PurchaseOrder po) {
        String prazo = po.getPaymentDueAt() == null ? "" : DATA.format(po.getPaymentDueAt());
        notifyUsersByRole(po.getBuyerCompanyId(), List.of(PersonaRole.FINANCEIRO), NotificationType.FATURA_GERADA,
                "Fatura pendente de pagamento",
                "A fatura " + invoice.getReference() + " (PO " + po.getReference() + ") foi gerada. Prazo de pagamento: " + prazo + ".",
                NotificationChannel.IN_APP_EMAIL, "Invoice", invoice.getId());
    }

    public void entregaDespachada(PurchaseOrder po) {
        notifyUsersByRole(po.getBuyerCompanyId(), List.of(PersonaRole.COMPRADOR), NotificationType.ENTREGA_DESPACHADA,
                "Entrega despachada", "A entrega da PO " + po.getReference() + " foi despachada.",
                NotificationChannel.IN_APP, "PurchaseOrder", po.getId());
    }

    public void poEntregue(PurchaseOrder po) {
        notifyUsersByRole(po.getBuyerCompanyId(), List.of(PersonaRole.COMPRADOR, PersonaRole.COMPANY_ADMIN),
                NotificationType.PO_ENTREGUE, "Entrega marcada como concluída pelo fornecedor",
                "O fornecedor marcou a entrega da PO " + po.getReference() + " como concluída. Confirme a receção.",
                NotificationChannel.IN_APP_EMAIL, "PurchaseOrder", po.getId());
    }

    public void poRecebidaConforme(PurchaseOrder po) {
        notifyUsersByRole(po.getSupplierCompanyId(), List.of(PersonaRole.FORNECEDOR, PersonaRole.COMPANY_ADMIN),
                NotificationType.PO_RECEBIDA_CONFORME, "Receção confirmada sem divergências",
                "O comprador confirmou a receção da PO " + po.getReference() + " sem divergências. A ordem foi concluída.",
                NotificationChannel.IN_APP_EMAIL, "PurchaseOrder", po.getId());
    }

    public void rececaoComDivergencia(PurchaseOrder po) {
        notifyUsersByRole(null, List.of(PersonaRole.ADMIN_SISTEMA), NotificationType.RECECAO_COM_DIVERGENCIA,
                "Receção com divergência reportada",
                "A PO " + po.getReference() + " foi recebida com divergência (\"" + po.getReceptionStatus()
                        + "\"). Caso a acompanhar fora da plataforma (sinistro).",
                NotificationChannel.IN_APP_EMAIL, "PurchaseOrder", po.getId());
    }

    public void divergenciaResolvida(PurchaseOrder po) {
        boolean reposicao = "REPOSICAO".equals(po.getDivergenceResolution());
        String notas = po.getDivergenceResolutionNotes();
        notifyUsersByRole(po.getSupplierCompanyId(), List.of(PersonaRole.FORNECEDOR, PersonaRole.COMPANY_ADMIN),
                NotificationType.DIVERGENCIA_RESOLVIDA, reposicao ? "Reposição solicitada" : "Divergência resolvida",
                reposicao
                        ? "A divergência da PO " + po.getReference() + " foi resolvida com pedido de REPOSIÇÃO: "
                        + "corrija/reentregue a mercadoria e volte a marcar como entregue."
                        + (notas != null && !notas.isBlank() ? " Notas: " + notas : "")
                        : "A divergência da PO " + po.getReference() + " foi resolvida: o comprador aceitou a entrega e a ordem foi concluída.",
                NotificationChannel.IN_APP_EMAIL, "PurchaseOrder", po.getId());

        notifyUsersByRole(null, List.of(PersonaRole.ADMIN_SISTEMA), NotificationType.DIVERGENCIA_RESOLVIDA,
                "Divergência resolvida",
                "A divergência da PO " + po.getReference() + " foi resolvida (" + (reposicao ? "reposição" : "aceite") + ").",
                NotificationChannel.IN_APP, "PurchaseOrder", po.getId());
    }

    public void estoqueBaixo(String supplierId, String productId, String productName, Integer stockQuantity, Integer minStock) {
        notifyUsersByRole(supplierId, List.of(PersonaRole.FORNECEDOR, PersonaRole.COMPANY_ADMIN), NotificationType.ESTOQUE_BAIXO,
                "Stock abaixo do mínimo",
                "O produto \"" + productName + "\" está com " + (stockQuantity == null ? 0 : stockQuantity)
                        + " unidades em stock, abaixo do mínimo definido (" + minStock + "). Considere repor.",
                NotificationChannel.IN_APP, "Product", productId);
    }

    public void supplierDevRecebida(SupplierDevRequest r) {
        notifyUsersByRole(null, List.of(PersonaRole.ADMIN_SISTEMA), NotificationType.SUPPLIER_DEV_RECEBIDA,
                "Nova candidatura ao Supplier Development",
                r.getCompanyName() + " candidatou-se ao programa (" + r.getReference() + "). Percurso: " + r.getTrack()
                        + ". Contacto: " + r.getContactName() + " — " + r.getContactEmail() + ". Taxa de acesso de "
                        + r.getAccessFeeUsd() + " USD emitida na submissão — por receber.",
                NotificationChannel.IN_APP_EMAIL, "SupplierDevRequest", r.getId());
    }

    public void pagamentoProcessado(Payment payment, PurchaseOrder po) {
        notifyUsersByRole(po.getSupplierCompanyId(), List.of(PersonaRole.FORNECEDOR, PersonaRole.COMPANY_ADMIN),
                NotificationType.PAGAMENTO_PROCESSADO, "Pagamento recebido",
                "O pagamento da PO " + po.getReference() + " foi processado. Pode iniciar a execução/entrega.",
                NotificationChannel.IN_APP_EMAIL, "Payment", payment.getId());
    }

    private static final java.util.Map<String, String> MENSAGENS_SUBSCRICAO_A_EXPIRAR = java.util.Map.of(
            "D30", "A subscrição da sua empresa vence em 30 dias.",
            "D7", "A subscrição da sua empresa vence em 7 dias. Renove para continuar a utilizar todos os recursos do plano.",
            "D3", "A subscrição da sua empresa vence em 3 dias. Renove para não perder acesso aos recursos pagos.",
            "D1", "A subscrição da sua empresa vence amanhã. Renove hoje para não interromper o serviço.",
            "D0", "A subscrição da sua empresa vence hoje. Envie o comprovativo de pagamento para não interromper o serviço.",
            "GRACE_INICIO", "A subscrição da sua empresa expirou. Os seus dados continuam seguros — envie o comprovativo de pagamento para renovar o acesso aos recursos pagos.",
            "GRACE_META", "A subscrição da sua empresa continua por regularizar. Os seus dados continuam seguros, mas os recursos pagos ficam indisponíveis em breve sem renovação.");

    public void subscricaoAExpirar(Company company, String tier) {
        String mensagem = MENSAGENS_SUBSCRICAO_A_EXPIRAR.getOrDefault(tier, MENSAGENS_SUBSCRICAO_A_EXPIRAR.get("D30"));
        notifyUsersByRole(company.getId(), List.of(PersonaRole.COMPANY_ADMIN, PersonaRole.FINANCEIRO),
                NotificationType.SUBSCRICAO_A_EXPIRAR, "Subscrição a vencer", mensagem,
                NotificationChannel.IN_APP_EMAIL, null, null);
    }
}
