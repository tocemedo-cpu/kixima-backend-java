package ao.kixima.support;

import ao.kixima.security.AdminArea;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.PersonaRole;
import ao.kixima.storage.StorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Espelha o troço "overview"/imagens de backend/src/routes/supportRoutes.js —
 * as categorias e FAQ da página de Ajuda, os canais, o horário, os locais de
 * imagem (com defaults versionados em /help e overrides do Admin que só
 * valem enquanto o ficheiro existir no disco).
 */
@Service
public class SupportOverviewService {

    public record Faq(String q, String a) {
    }

    public record Categoria(String key, String title, String desc, String icon, List<Faq> faq) {
    }

    public record Canal(String key, String label, String value, String action, String icon) {
    }

    public record Slot(String key, String label, String group) {
    }

    static final List<Categoria> CATEGORIES = List.of(
            new Categoria("ordens", "Ordens de Compra", "Criar, aprovar e acompanhar", "orders", List.of(
                    new Faq("Como crio uma ordem de compra?", "No menu Pedidos, selecione os produtos a partir do Catálogo, defina as quantidades e submeta a ordem para aprovação."),
                    new Faq("Como acompanho o estado de uma ordem?", "Em Pedidos, cada ordem mostra o seu estado atual (pendente, aprovada, recebida). Abra a ordem para ver o detalhe de cada linha."),
                    new Faq("Posso visualizar a ordem antes de a imprimir?", "Sim. Abra a ordem em Pedidos para a pré-visualizar por completo e só depois imprimir ou exportar."))),
            new Categoria("faturacao", "Faturação", "Faturas e faturação garantida", "invoice", List.of(
                    new Faq("O que é a faturação garantida?", "A KIXIMA assegura o pagamento ao fornecedor depois de a receção da mercadoria ser confirmada, reduzindo o risco de crédito da transação."),
                    new Faq("Como visualizo uma fatura antes de imprimir?", "Em Financeiro › Faturas, abra a fatura para a pré-visualizar antes de imprimir ou exportar."),
                    new Faq("Quem emite as faturas?", "O fornecedor emite a fatura associada a uma ordem de compra já recebida."))),
            new Categoria("pagamentos", "Pagamentos", "Registo e histórico", "payment", List.of(
                    new Faq("Como registo um pagamento?", "O perfil Financeiro regista o pagamento a partir da fatura correspondente, em Financeiro › Pagamentos."),
                    new Faq("Onde consulto o histórico de pagamentos?", "Em Financeiro › Pagamentos encontra todos os movimentos, com o estado e a data de cada um."))),
            new Categoria("contratos", "Contratos", "Contratos e limites", "contract", List.of(
                    new Faq("Como funcionam os limites de um contrato?", "Cada contrato define limites que balizam as ordens de compra permitidas dentro do seu âmbito."),
                    new Faq("Onde consulto os meus contratos?", "No menu Documentação encontra os contratos disponíveis para o seu perfil."))),
            new Categoria("catalogo", "Catálogo", "Pesquisar e publicar", "catalog", List.of(
                    new Faq("Como pesquiso no catálogo?", "Em Catálogo, utilize a pesquisa e os filtros por categoria para encontrar produtos e serviços."),
                    new Faq("Como publico um produto? (fornecedor)", "Em Catálogo, use a opção de novo produto e preencha os campos obrigatórios do produto."))),
            new Categoria("conta", "Conta & Acesso", "Utilizadores e permissões", "users", List.of(
                    new Faq("Como altero a minha palavra-passe?", "Aceda a Configurações › Conta para atualizar a sua palavra-passe."),
                    new Faq("Quem gere os utilizadores da empresa?", "O administrador da empresa gere os utilizadores e as respetivas permissões de acesso."))));

    static final List<Canal> CHANNELS = List.of(
            new Canal("chat", "Chat Online", "Converse connosco", "Iniciar chat", "help"),
            new Canal("email", "E-mail Support", "suporte@kixima.com", "Enviar e-mail", "policy"),
            new Canal("telefone", "Telefone", "+244 923 456 789", "Ligar agora", "building"),
            new Canal("whatsapp", "WhatsApp", "+244 923 456 789", "Iniciar conversa", "help"));

    static final Map<String, Object> HOURS;
    static final List<Slot> IMAGE_SLOTS;
    static final Set<String> SLOT_KEYS;
    static final Map<String, String> DEFAULT_IMAGES;
    private static final List<SupportStatus> ABERTOS = List.of(SupportStatus.ABERTO, SupportStatus.EM_ANDAMENTO, SupportStatus.AGUARDANDO_RESPOSTA);

    static {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("label", "Seg - Sex: 08:00 - 18:00");
        h.put("tz", "GMT +1 (África/Luanda)");
        h.put("online", true);
        HOURS = Map.copyOf(h);

        List<Slot> slots = new ArrayList<>(List.of(
                new Slot("hero", "Ilustração principal", "Destaque"),
                new Slot("mascot", "Ilustração \"Ainda precisa de ajuda?\"", "Destaque"),
                new Slot("quick_kb", "Perguntas Frequentes", "Atalhos"),
                new Slot("quick_contact", "Contato com Suporte", "Atalhos"),
                new Slot("quick_tickets", "Tickets Abertos", "Atalhos")));
        for (Categoria c : CATEGORIES) slots.add(new Slot(c.key(), c.title(), "Categorias"));
        for (Canal c : CHANNELS) slots.add(new Slot("channel_" + c.key(), c.label(), "Canais"));
        IMAGE_SLOTS = List.copyOf(slots);
        SLOT_KEYS = slots.stream().map(Slot::key).collect(Collectors.toUnmodifiableSet());

        Map<String, String> d = new LinkedHashMap<>();
        d.put("hero", "/help/hero.png");
        d.put("mascot", "/help/mascot.png");
        d.put("quick_kb", "/help/quick_kb.jpg");
        d.put("quick_contact", "/help/quick_contact.png");
        d.put("quick_tickets", "/help/quick_tickets.jpg");
        d.put("ordens", "/help/ordens.png");
        d.put("faturacao", "/help/faturacao.jpg");
        d.put("pagamentos", "/help/pagamentos.jpg");
        d.put("contratos", "/help/contratos.jpg");
        d.put("catalogo", "/help/catalogo.jpg");
        d.put("conta", "/help/conta.jpg");
        d.put("channel_chat", "/help/channel_chat.jpg");
        d.put("channel_email", "/help/channel_email.jpg");
        d.put("channel_telefone", "/help/channel_telefone.png");
        d.put("channel_whatsapp", "/help/channel_whatsapp.png");
        DEFAULT_IMAGES = Map.copyOf(d);
    }

    private final SupportTicketRepository ticketRepository;
    private final SupportCategoryImageRepository imageRepository;
    private final StorageService storageService;

    public SupportOverviewService(SupportTicketRepository ticketRepository, SupportCategoryImageRepository imageRepository,
                                  StorageService storageService) {
        this.ticketRepository = ticketRepository;
        this.imageRepository = imageRepository;
        this.storageService = storageService;
    }

    public static boolean slotValido(String key) {
        return SLOT_KEYS.contains(key);
    }

    /** ADMIN_SISTEMA com a área Suporte (ou Super Admin, sem áreas). */
    public static boolean podeGerirSuporte(CurrentUser user) {
        if (user == null || user.role() != PersonaRole.ADMIN_SISTEMA) return false;
        List<String> areas = user.adminAreas() == null ? List.of() : user.adminAreas();
        return areas.isEmpty() || areas.contains(AdminArea.SUPORTE);
    }

    /** Defaults versionados + overrides ainda vivos no disco (um upload apagado num deploy volta ao default). */
    Map<String, String> loadImageMap() {
        Map<String, String> m = new LinkedHashMap<>(DEFAULT_IMAGES);
        for (SupportCategoryImage r : imageRepository.findAll()) {
            if (storageService.urlAindaVivo(r.getImageUrl())) m.put(r.getKey(), r.getImageUrl());
        }
        return m;
    }

    private static Map<String, Object> categoria(Categoria c, Map<String, String> imgByKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", c.key());
        m.put("title", c.title());
        m.put("desc", c.desc());
        m.put("icon", c.icon());
        List<Map<String, String>> faq = new ArrayList<>();
        for (Faq f : c.faq()) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("q", f.q());
            q.put("a", f.a());
            faq.add(q);
        }
        m.put("faq", faq);
        m.put("count", c.faq().size());
        m.put("imageUrl", imgByKey.get(c.key()));
        return m;
    }

    private static Map<String, Object> canal(Canal c, Map<String, String> imgByKey, boolean comImagem) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", c.key());
        m.put("label", c.label());
        m.put("value", c.value());
        m.put("action", c.action());
        m.put("icon", c.icon());
        if (comImagem) m.put("imageUrl", imgByKey.get("channel_" + c.key()));
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview(CurrentUser user) {
        long open = ticketRepository.countByUserIdAndStatusIn(user.id(), ABERTOS);
        Map<String, String> imgByKey = loadImageMap();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", CATEGORIES.stream().map(c -> categoria(c, imgByKey)).toList());
        out.put("channels", CHANNELS.stream().map(c -> canal(c, imgByKey, true)).toList());
        out.put("hours", HOURS);
        out.put("system", Map.of("operational", true));
        out.put("faqCount", CATEGORIES.stream().mapToInt(c -> c.faq().size()).sum());
        out.put("images", imgByKey);
        out.put("openTickets", open);
        out.put("canManageImages", podeGerirSuporte(user));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> adminOverview() {
        Map<String, Long> by = new HashMap<>();
        long total = 0;
        for (Object[] linha : ticketRepository.contagemPorStatus()) {
            long n = ((Number) linha[1]).longValue();
            by.put(((SupportStatus) linha[0]).name(), n);
            total += n;
        }
        Map<String, String> imgByKey = loadImageMap();
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("total", total);
        kpis.put("abertos", by.getOrDefault("ABERTO", 0L));
        kpis.put("emAndamento", by.getOrDefault("EM_ANDAMENTO", 0L));
        kpis.put("aguardando", by.getOrDefault("AGUARDANDO_RESPOSTA", 0L));
        kpis.put("resolvidos", by.getOrDefault("RESOLVIDO", 0L));
        kpis.put("fechados", by.getOrDefault("FECHADO", 0L));

        List<Map<String, Object>> slots = new ArrayList<>();
        for (Slot s : IMAGE_SLOTS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.key());
            m.put("label", s.label());
            m.put("group", s.group());
            m.put("imageUrl", imgByKey.get(s.key()));
            slots.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", CATEGORIES.stream().map(c -> categoria(c, imgByKey)).toList());
        out.put("channels", CHANNELS.stream().map(c -> canal(c, imgByKey, false)).toList());
        out.put("hours", HOURS);
        out.put("imageSlots", slots);
        out.put("kpis", kpis);
        return out;
    }

    /** upsert({ where: { key }, create, update }) — a linha da imagem de um local. */
    @Transactional
    public Map<String, Object> guardarImagem(String key, String imageUrl) {
        SupportCategoryImage row = imageRepository.findById(key).orElse(null);
        if (row == null) {
            row = new SupportCategoryImage(key, imageUrl, java.time.Instant.now());
            imageRepository.save(row);
        } else {
            row.setImageUrl(imageUrl);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", row.getKey());
        m.put("imageUrl", row.getImageUrl());
        m.put("updatedAt", row.getUpdatedAt());
        return m;
    }

    @Transactional
    public void removerImagem(String key) {
        imageRepository.findById(key).ifPresent(imageRepository::delete);
    }
}
