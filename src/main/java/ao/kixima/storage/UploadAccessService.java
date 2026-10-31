package ao.kixima.storage;

import ao.kixima.catalog.ProductDocumentRepository;
import ao.kixima.catalog.ProductImageRepository;
import ao.kixima.catalog.ProductRepository;
import ao.kixima.company.CompanyDocumentRepository;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.SupplierToKiximaPolicyRepository;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.PersonaRole;
import ao.kixima.support.SupportChatService;
import ao.kixima.support.SupportMessageRepository;
import ao.kixima.user.UserRepository;
import org.springframework.stereotype.Service;

/**
 * Espelha backend/src/services/uploadAccessService.js — quem pode ver um
 * ficheiro servido por {@code GET /api/uploads/:filename}. Por omissão
 * NEGA-SE: um ficheiro que não é referenciado por nenhuma tabela conhecida
 * não é servido a ninguém que não seja Admin do Sistema.
 *
 * ÂMBITO: público (imagem de capa/galeria/documento de produto, avatar,
 * logótipo de empresa) + Admin do Sistema + anexos do Chat de Suporte +
 * os documentos de credenciamento da PRÓPRIA empresa (certidão, alvará,
 * licença ANPG, apólice — enviados no cadastro por etapas), que só a
 * empresa que os enviou e a KIXIMA abrem. NÃO PORTADO — fica a negar por
 * omissão até os respectivos domínios existirem em Java: comprovativos de
 * subscrição/pagamento (PlanoCobranca/AddonCobranca/Payment) e anexos do
 * Chat Comercial (ConversationMessage).
 */
@Service
public class UploadAccessService {

    public record Acesso(boolean publico, boolean permitido) {
        static final Acesso PUBLICO = new Acesso(true, true);
        static final Acesso PERMITIDO = new Acesso(false, true);
        static final Acesso NEGADO = new Acesso(false, false);
    }

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductDocumentRepository productDocumentRepository;
    private final UserRepository userRepository;
    private final SupportMessageRepository supportMessageRepository;
    private final SupportChatService supportChatService;
    private final CompanyRepository companyRepository;
    private final CompanyDocumentRepository companyDocumentRepository;
    private final SupplierToKiximaPolicyRepository supplierPolicyRepository;

    public UploadAccessService(ProductRepository productRepository, ProductImageRepository productImageRepository,
                                ProductDocumentRepository productDocumentRepository, UserRepository userRepository,
                                SupportMessageRepository supportMessageRepository, SupportChatService supportChatService,
                                CompanyRepository companyRepository, CompanyDocumentRepository companyDocumentRepository,
                                SupplierToKiximaPolicyRepository supplierPolicyRepository) {
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
        this.productDocumentRepository = productDocumentRepository;
        this.userRepository = userRepository;
        this.supportMessageRepository = supportMessageRepository;
        this.supportChatService = supportChatService;
        this.companyRepository = companyRepository;
        this.companyDocumentRepository = companyDocumentRepository;
        this.supplierPolicyRepository = supplierPolicyRepository;
    }

    private boolean ehPublico(String url) {
        return productRepository.existsByImageUrl(url)
                || productImageRepository.existsByUrl(url)
                || productDocumentRepository.existsByFileUrl(url)
                || userRepository.existsByAvatarUrl(url)
                || companyRepository.existsByLogoUrl(url);
    }

    private boolean ehDaEmpresa(String url, String companyId) {
        return companyDocumentRepository.existsByFileUrlAndCompanyId(url, companyId)
                || supplierPolicyRepository.existsByDocumentUrlAndCompanyId(url, companyId)
                || companyRepository.existsByIdAndOnboardingDraftContaining(companyId, url);
    }

    public Acesso resolverAcesso(String filename, CurrentUser user) {
        String url = "/api/uploads/" + filename;

        if (ehPublico(url)) return Acesso.PUBLICO;
        if (user != null && user.role() == PersonaRole.ADMIN_SISTEMA) return Acesso.PERMITIDO;
        if (user == null) return Acesso.NEGADO;

        // Documentos de credenciamento: só a empresa que os enviou (a KIXIMA já passou acima).
        if (user.companyId() != null && ehDaEmpresa(url, user.companyId())) return Acesso.PERMITIDO;

        var mensagem = supportMessageRepository.findFirstByAttachmentUrl(url).orElse(null);
        if (mensagem != null) {
            try {
                supportChatService.ticketComAcesso(mensagem.getTicketId(), user);
                return Acesso.PERMITIDO;
            } catch (RuntimeException e) {
                return Acesso.NEGADO;
            }
        }

        // Nenhuma tabela conhecida referencia este ficheiro — nega-se (ver Javadoc da classe).
        return Acesso.NEGADO;
    }
}
