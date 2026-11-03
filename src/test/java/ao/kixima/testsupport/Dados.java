package ao.kixima.testsupport;

import ao.kixima.testsupport.Contas.Persona;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dados de negócio dos testes, criados DURANTE a execução (uma vez por JVM, por {@link Contas}) e apagados no fim:
 * duas empresas (cliente e fornecedora), o catálogo da fornecedora, ordens de compra em todos os estados do ciclo de
 * vida, faturas e pagamentos, contratos, apólices, documentos de credenciamento e pedidos de suporte.
 *
 * Substitui os «dados de exemplo» que antes tinham de ser inseridos à mão na base de testes. Regras:
 * <ul>
 *   <li>Tudo tem identificadores únicos desta execução (NIF, slugs, referências {@code PO-T<sufixo>-…}), por isso
 *   convive com dados que já existam na base sem colidir, e as referências nunca coincidem com as que a aplicação gera
 *   ({@code PO-2026-…}).</li>
 *   <li>As empresas e utilizadores levam o domínio reservado {@link Contas#DOMINIO}; o que for criado a partir delas
 *   (inclusive por testes sem rollback) apaga-se em cascata no fim.</li>
 *   <li>Os testes {@code @Transactional} alteram estes dados à vontade: o rollback repõe-nos.</li>
 *   <li>Nada aqui contém utilizadores, emails ou senhas: esses vêm de {@link Contas}.</li>
 * </ul>
 */
public final class Dados {

    private static final String SUFIXO = Long.toString(Math.abs(new java.security.SecureRandom().nextLong()), 36).toUpperCase();
    private static final List<String> LIMIARES_CRIADOS = new ArrayList<>();

    private static String idCliente;
    private static String idFornecedora;
    private static String nifCliente;
    private static String nifFornecedora;
    private static String nomeCliente;
    private static String nomeFornecedora;

    /** Nome do ficheiro da certidão comercial da empresa fornecedora (para os testes que listam os seus documentos). */
    public static final String NOME_CERTIDAO_FORNECEDORA = "certidao-fornecedora.pdf";

    private Dados() {
    }

    // --- acessos (o que os testes precisam de saber) ----------------------------------------------------------------

    public static String idCliente() {
        Contas.email(Persona.COMPRADOR);
        return idCliente;
    }

    public static String idFornecedora() {
        Contas.email(Persona.COMPRADOR);
        return idFornecedora;
    }

    public static String nifCliente() {
        Contas.email(Persona.COMPRADOR);
        return nifCliente;
    }

    public static String nifFornecedora() {
        Contas.email(Persona.COMPRADOR);
        return nifFornecedora;
    }

    public static String nomeCliente() {
        Contas.email(Persona.COMPRADOR);
        return nomeCliente;
    }

    public static String nomeFornecedora() {
        Contas.email(Persona.COMPRADOR);
        return nomeFornecedora;
    }

    /** Referência da ordem de compra n (1 a 8): 1 aguarda aprovação, 2 aguarda pagamento, 3 paga, 4 em execução… */
    public static String refPo(int n) {
        return "PO-T" + SUFIXO + "-" + String.format("%05d", n);
    }

    /** Referência da fatura n (1 a 6), das ordens 2 a 7: a 1 está pendente (2 394 000 AOA), as outras pagas. */
    public static String refFatura(int n) {
        return "FAT-T" + SUFIXO + "-" + String.format("%05d", n);
    }

    public static String refPagamento(int n) {
        return "PAY-T" + SUFIXO + "-" + String.format("%05d", n);
    }

    public static String refContrato(int n) {
        return "CTR-T" + SUFIXO + "-" + String.format("%04d", n);
    }

    public static String refSuporte(int n) {
        return "SUP-T" + SUFIXO + "-" + String.format("%05d", n);
    }

    // --- criação (chamada por Contas, com a ligação à base de testes) ---------------------------------------------------

    /** Cria as duas empresas (cliente e fornecedora) e devolve os ids {cliente, fornecedora}. */
    static String[] criarEmpresas(Connection c) throws SQLException {
        nifCliente = "AO-CLI-" + SUFIXO;
        nifFornecedora = "AO-FOR-" + SUFIXO;
        nomeCliente = "Operadora de teste " + SUFIXO;
        nomeFornecedora = "Fornecedora de teste " + SUFIXO;
        idCliente = UUID.randomUUID().toString();
        idFornecedora = UUID.randomUUID().toString();
        LocalDateTime agora = LocalDateTime.now();
        inserir(c, "companies", Map.ofEntries(
                Map.entry("id", idCliente), Map.entry("name", nomeCliente), Map.entry("tax_id", nifCliente),
                Map.entry("type", E("CLIENTE")), Map.entry("status", E("APROVADA")), Map.entry("contact_email", Contas.emailUnico("contacto-operadora")),
                Map.entry("verified", false), Map.entry("country", "Angola"), Map.entry("size", E("PEQUENA")), Map.entry("plan", E("CORE")),
                Map.entry("seat_price_usd", new BigDecimal("100")), Map.entry("search_rank", 1), Map.entry("approved_at", agora),
                Map.entry("terms_accepted_at", agora), Map.entry("created_at", agora), Map.entry("updated_at", agora)));
        inserir(c, "companies", Map.ofEntries(
                Map.entry("id", idFornecedora), Map.entry("name", nomeFornecedora), Map.entry("tax_id", nifFornecedora),
                Map.entry("type", E("FORNECEDOR")), Map.entry("status", E("APROVADA")), Map.entry("contact_email", Contas.emailUnico("contacto-fornecedora")),
                Map.entry("verified", true), Map.entry("country", "Angola"), Map.entry("city", "Luanda"), Map.entry("province", "Luanda"),
                Map.entry("bank_name", "Banco de teste"), Map.entry("iban", "AO06" + String.format("%021d", Math.abs((long) SUFIXO.hashCode()))),
                Map.entry("size", E("PEQUENA")), Map.entry("plan", E("CORE")), Map.entry("seat_price_usd", new BigDecimal("100")),
                Map.entry("search_rank", 1), Map.entry("approved_at", agora), Map.entry("terms_accepted_at", agora),
                Map.entry("created_at", agora), Map.entry("updated_at", agora)));
        return new String[]{idCliente, idFornecedora};
    }

    /** Cria o resto do cenário. {@code utilizadores}: persona → id do utilizador temporário. */
    static void criarCenario(Connection c, Map<Persona, String> utilizadores) throws SQLException {
        LocalDateTime agora = LocalDateTime.now();
        String comprador = utilizadores.get(Persona.COMPRADOR);
        String admEmpresa = utilizadores.get(Persona.COMPANY_ADMIN);
        String financeiro = utilizadores.get(Persona.FINANCEIRO);
        String fornecedor = utilizadores.get(Persona.FORNECEDOR);
        String admSistema = utilizadores.get(Persona.ADMIN_SISTEMA);

        // Documentos de credenciamento e apólices.
        documento(c, idCliente, "CERTIDAO_COMERCIAL", "certidao-comercial.pdf");
        documento(c, idCliente, "ALVARA_COMERCIAL", "alvara-comercial.pdf");
        documento(c, idFornecedora, "CERTIDAO_COMERCIAL", NOME_CERTIDAO_FORNECEDORA);
        documento(c, idFornecedora, "LICENCA_ANPG", "licenca-anpg.pdf");
        inserir(c, "supplier_to_kixima_policies", Map.of("id", id(), "company_id", idFornecedora, "policy_number", "FOR-KIX-T" + SUFIXO,
                "insurer", "Seguradora de teste", "coverage_amount", new BigDecimal("5000000"), "currency", "AOA", "status", E("APROVADA"),
                "valid_from", agora.minusMonths(6), "valid_until", agora.plusMonths(6), "updated_at", agora));
        inserir(c, "kixima_to_client_policies", Map.ofEntries(Map.entry("id", id()), Map.entry("company_id", idCliente),
                Map.entry("policy_number", "KIX-CLI-T" + SUFIXO), Map.entry("insurer", "Seguradora de teste"),
                Map.entry("coverage_amount", new BigDecimal("20000000")), Map.entry("currency", "AOA"), Map.entry("status", E("APROVADA")),
                Map.entry("issued_by_id", admSistema), Map.entry("valid_from", agora.minusMonths(6)), Map.entry("valid_until", agora.plusMonths(6)),
                Map.entry("updated_at", agora)));

        // Catálogo: 5 serviços e 4 produtos da fornecedora.
        String[] produtos = new String[9];
        produtos[0] = produto(c, 0, "PRODUTO", "Válvula de esfera 4\" API 6D", "Válvulas", null, "850000", 4.8, 28, 10, "Válvula de esfera flangeada, corpo em aço carbono.", "Em stock", 15, "ISO 9001", "ISO 45001");
        produtos[1] = produto(c, 1, "PRODUTO", "Mangueira hidráulica de alta pressão 2\"", "Hidráulica", null, "120000", 4.6, 15, 0, "Mangueira reforçada, 350 bar, 10 metros.", "Em stock", 7, "ISO 45001");
        produtos[2] = produto(c, 2, "SERVICO", "Inspeção por Ultrassom (UT)", "Inspeção & Ensaios", "Inspeção & Ensaios", "950000", 4.9, 34, 43, "Serviço de inspeção por ultrassom convencional e phased array.", "Disponível", 5, "API Q1 / Q2", "ISO 14001");
        produtos[3] = produto(c, 3, "SERVICO", "Ensaios não Destrutivos (NDT)", "Inspeção & Ensaios", "Inspeção & Ensaios", "1250000", 4.7, 12, 0, "Ensaios não destrutivos: RT, PT, MT, UT, TOFD.", "Disponível", 10, "ISO 9001");
        produtos[4] = produto(c, 4, "SERVICO", "Transporte de Carga Industrial", "Logística & Transporte", "Logística & Transporte", "480000", 4.5, 8, 0, "Transporte de equipamentos e carga pesada.", "Disponível", 3, "ISO 9001");
        produtos[5] = produto(c, 5, "SERVICO", "Engenharia de Detalhe (Oil & Gas)", "Engenharia", "Engenharia", "3200000", 4.7, 6, 16, "Engenharia básica e detalhada para projetos de óleo e gás.", "Sob Consulta", 20, "ISO 9001");
        produtos[6] = produto(c, 6, "PRODUTO", "Gerador Diesel 500 kVA", "Equipamentos", null, "2100000", 4.4, 9, 0, "Gerador industrial 500 kVA, insonorizado, com manutenção incluída.", "Em stock", 12, "API Q1 / Q2");
        produtos[7] = produto(c, 7, "PRODUTO", "Tubos de Aço Carbono (lote)", "Materiais", null, "1650000", 4.6, 11, 0, "Tubos sem costura para linhas de processo, conforme ASTM.", "Em stock", 25, "ISO 9001");
        produtos[8] = produto(c, 8, "SERVICO", "Formação & Certificação Técnica", "Formação & Certificação", "Formação & Certificação", "380000", 4.5, 4, 0, "Formação técnica e certificação para profissionais.", "Disponível", 14, "ISO 9001");

        // Ordens de compra: uma por estado do ciclo de vida; faturas das ordens 2 a 7 e pagamentos das pagas.
        // {n, produto, quantidade, preço unitário, estado}
        Object[][] ordens = {
                {1, 0, 2, "850000", "AGUARDANDO_APROVACAO"}, {2, 6, 1, "2100000", "AGUARDANDO_PAGAMENTO"}, {3, 1, 5, "120000", "PAGA"},
                {4, 7, 3, "1650000", "EM_EXECUCAO"}, {5, 2, 1, "950000", "ENTREGUE"}, {6, 3, 1, "1250000", "RECEBIDA_CONFORME"},
                {7, 4, 2, "480000", "RECEBIDA_COM_DIVERGENCIA"}, {8, 5, 1, "3200000", "REJEITADA"}};
        String[] poIds = new String[9];
        for (Object[] o : ordens) {
            int n = (Integer) o[0];
            String estado = (String) o[4];
            BigDecimal unit = new BigDecimal((String) o[3]);
            BigDecimal total = unit.multiply(BigDecimal.valueOf((Integer) o[2]));
            String poId = id();
            poIds[n] = poId;
            java.util.Map<String, Object> po = new java.util.LinkedHashMap<>();
            po.put("id", poId); po.put("reference", refPo(n)); po.put("buyer_company_id", idCliente); po.put("supplier_company_id", idFornecedora);
            po.put("created_by_id", comprador); po.put("status", E(estado)); po.put("total_amount", total); po.put("currency", "AOA");
            po.put("is_call_off", false); po.put("erp_managed", false); po.put("created_by_source", "HUMANO");
            po.put("created_at", agora.minusDays(10)); po.put("updated_at", agora);
            if (n >= 2) { po.put("approved_by_id", admEmpresa); po.put("approved_at", agora.minusDays(9)); }
            if (n >= 2 && n <= 7) { po.put("accepted_at", agora.minusDays(8)); po.put("payment_due_at", agora.plusDays(7)); }
            if (n >= 3 && n <= 7) po.put("paid_at", agora.minusDays(5));
            if (n >= 4 && n <= 7) po.put("dispatched_at", agora.minusDays(4));
            if (n >= 5 && n <= 7) po.put("delivered_at", agora.minusDays(2));
            if (n == 6 || n == 7) po.put("received_at", agora.minusDays(1));
            inserir(c, "purchase_orders", po);
            inserir(c, "purchase_order_items", Map.of("id", id(), "purchase_order_id", poId, "product_id", produtos[(Integer) o[1]],
                    "quantity", o[2], "unit_price", unit, "line_total", total));
        }
        // Faturas: {n, ordem, líquido, IVA, retenção, estado}
        Object[][] faturas = {
                {1, 2, "2100000", "294000", "0", "PENDENTE"}, {2, 3, "600000", "84000", "0", "PAGA"}, {3, 4, "4950000", "693000", "0", "PAGA"},
                {4, 5, "950000", "133000", "61750", "PAGA"}, {5, 6, "1250000", "175000", "81250", "PAGA"}, {6, 7, "960000", "134400", "62400", "PAGA"}};
        for (Object[] f : faturas) {
            int n = (Integer) f[0];
            BigDecimal liquido = new BigDecimal((String) f[2]);
            BigDecimal iva = new BigDecimal((String) f[3]);
            BigDecimal total = liquido.add(iva);
            String faturaId = id();
            Array vazio = c.createArrayOf("text", new String[0]);
            inserir(c, "invoices", Map.ofEntries(
                    Map.entry("id", faturaId), Map.entry("reference", refFatura(n)), Map.entry("purchase_order_id", poIds[(Integer) f[1]]),
                    Map.entry("amount", total), Map.entry("net_amount", liquido), Map.entry("tax_amount", iva),
                    Map.entry("withholding_amount", new BigDecimal((String) f[4])), Map.entry("currency", "AOA"), Map.entry("status", E((String) f[5])),
                    Map.entry("issued_at", agora.minusDays(8)), Map.entry("due_at", agora.plusDays(7)), Map.entry("consolidated_po_ids", vazio),
                    Map.entry("updated_at", agora)));
            if (n >= 2) {
                inserir(c, "payments", Map.of("id", id(), "invoice_id", faturaId, "amount", total, "currency", "AOA", "status", E("PROCESSADO"),
                        "canal", E("TRANSFERENCIA_MANUAL"), "reference", refPagamento(n), "processed_by_id", financeiro, "processed_at", agora.minusDays(5)));
            }
        }
        // Contratos-quadro.
        contrato(c, 1, "TRIMESTRAL", "ATIVO", "Inspeção & Ensaios", "15300000", "4200000", 30, agora.minusMonths(4), agora.plusMonths(8));
        contrato(c, 2, "SEMESTRAL", "ATIVO", "Equipamentos", "8750000", "8750000", 45, agora.minusMonths(3), agora.plusDays(20));
        contrato(c, 3, "TRIMESTRAL", "EXPIRADO", "Logística & Transporte", "6800000", "6800000", 30, agora.minusMonths(13), agora.minusMonths(1));
        // Pedidos de suporte: {estado, categoria, assunto, autor}
        String[][] pedidos = {
                {"EM_ANDAMENTO", "Contratos", "Dúvida sobre call-off", comprador}, {"ABERTO", "Documentos", "Problema ao anexar documentos", fornecedor},
                {"AGUARDANDO_RESPOSTA", "Faturação", "Falha ao criar fatura", financeiro}, {"FECHADO", "Conta & Acesso", "Acesso ao relatório comercial", comprador},
                {"RESOLVIDO", "Conta & Acesso", "Adicionar novo utilizador", admEmpresa}, {"ABERTO", "Pagamentos", "Pagamento em atraso", fornecedor}};
        for (int i = 0; i < pedidos.length; i++) {
            inserir(c, "support_tickets", Map.of("id", id(), "reference", refSuporte(i + 1), "user_id", pedidos[i][3], "subject", pedidos[i][2],
                    "category", pedidos[i][1], "message", "Pedido de teste.", "status", E(pedidos[i][0]), "created_at", agora.minusDays(3), "updated_at", agora));
        }
        // Patamares de desconto por volume (tabela GLOBAL): só se não existirem, e só se apagam os que se criaram aqui.
        garantirPatamares(c, agora);
    }

    private static void garantirPatamares(Connection c, LocalDateTime agora) throws SQLException {
        try (var st = c.createStatement(); var rs = st.executeQuery("SELECT count(*) FROM discount_thresholds")) {
            rs.next();
            if (rs.getInt(1) > 0) return;
        }
        String[][] p = {{"100000", "2.5"}, {"500000", "5"}, {"1000000", "10"}};
        for (String[] t : p) {
            String id = id();
            inserir(c, "discount_thresholds", Map.of("id", id, "min_volume_usd", new BigDecimal(t[0]), "discount_percent", new BigDecimal(t[1]),
                    "ativo", true, "created_at", agora, "updated_at", agora));
            LIMIARES_CRIADOS.add(id);
        }
    }

    /** Apaga os patamares criados por este cenário (os que já existiam na base ficam). */
    static void apagarGlobais(Connection c) throws SQLException {
        for (String id : LIMIARES_CRIADOS) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM discount_thresholds WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        }
    }

    // --- pequenas peças -------------------------------------------------------------------------------------------------

    private static void documento(Connection c, String empresa, String tipo, String nome) throws SQLException {
        inserir(c, "company_documents", Map.of("id", id(), "company_id", empresa, "type", E(tipo), "file_url", "/api/uploads/" + id() + ".pdf",
                "original_name", nome, "created_at", LocalDateTime.now()));
    }

    private static void contrato(Connection c, int n, String periodicidade, String estado, String categoria, String total, String usado, int prazo,
                                 LocalDateTime de, LocalDateTime ate) throws SQLException {
        inserir(c, "contracts", Map.ofEntries(
                Map.entry("id", id()), Map.entry("reference", refContrato(n)), Map.entry("client_company_id", idCliente),
                Map.entry("supplier_company_id", idFornecedora), Map.entry("categories_covered", array(c, categoria)),
                Map.entry("total_value", new BigDecimal(total)), Map.entry("used_value", new BigDecimal(usado)), Map.entry("currency", "AOA"),
                Map.entry("billing_periodicity", E(periodicidade)), Map.entry("payment_term_days", prazo), Map.entry("status", E(estado)),
                Map.entry("valid_from", de), Map.entry("valid_until", ate), Map.entry("created_at", de), Map.entry("updated_at", LocalDateTime.now())));
    }

    private static String produto(Connection c, int i, String tipo, String nome, String categoria, String especialidade, String preco, double rating,
                                  int avaliacoes, int vistas, String descricao, String disponibilidade, int prazoDias, String... certificacoes) throws SQLException {
        String id = id();
        java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("id", id); p.put("supplier_id", idFornecedora); p.put("name", nome);
        p.put("slug", nome.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "") + "-" + i + "-" + SUFIXO.toLowerCase());
        p.put("kind", E(tipo)); p.put("category", categoria); p.put("unit_price", new BigDecimal(preco)); p.put("currency", "AOA");
        p.put("active", true); p.put("rating", rating); p.put("review_count", avaliacoes); p.put("view_count", vistas);
        p.put("description", descricao); p.put("availability", disponibilidade); p.put("lead_time_days", prazoDias);
        p.put("certifications", array(c, certificacoes)); p.put("tags", array(c, "oil & gas", categoria.toLowerCase()));
        p.put("image_url", "/catalog/teste-" + i + ".jpg"); p.put("city", "Luanda"); p.put("province", "Luanda"); p.put("country", "Angola");
        if (especialidade != null) p.put("specialty", especialidade);
        p.put("created_at", LocalDateTime.now().minusDays(20 - i)); p.put("updated_at", LocalDateTime.now());
        inserir(c, "products", p);
        return id;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static Array array(Connection c, String... valores) throws SQLException {
        return c.createArrayOf("text", valores);
    }

    /** Valor de uma coluna enum do Postgres (enviado como texto sem tipo; o servidor converte). */
    private record E(String valor) {
    }

    private static E E(String valor) {
        return new E(valor);
    }

    private static void inserir(Connection c, String tabela, Map<String, ?> valores) throws SQLException {
        List<String> colunas = new ArrayList<>(valores.keySet());
        String sql = "INSERT INTO " + tabela + " (" + String.join(", ", colunas) + ") VALUES ("
                + "?, ".repeat(colunas.size() - 1) + "?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < colunas.size(); i++) {
                Object v = valores.get(colunas.get(i));
                if (v instanceof E e) ps.setObject(i + 1, e.valor(), Types.OTHER);
                else if (v instanceof LocalDateTime t) ps.setObject(i + 1, t);
                else ps.setObject(i + 1, v);
            }
            ps.executeUpdate();
        }
    }
}
