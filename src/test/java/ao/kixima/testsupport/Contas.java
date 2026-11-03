package ao.kixima.testsupport;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Utilizadores (e o mundo à volta deles) dos testes, criados DURANTE a execução e removidos no fim. Nenhum email, senha
 * ou dado de negócio está escrito no código, nas migrações, nos seeds ou na configuração: cada execução gera os seus
 * (emails com sufixo aleatório e uma senha aleatória), insere-os na base de testes e um gancho de saída da JVM apaga-os.
 *
 * <ul>
 *   <li>Cria sempre as SUAS empresas (uma cliente e uma fornecedora) e os utilizadores nelas — nunca usa nem altera
 *   empresas ou utilizadores que já existam na base. Os dados de negócio (catálogo, ordens, faturas…) vêm de {@link Dados}.</li>
 *   <li>Só toca na base de testes (a ligação do perfil {@code test}, ou {@code -Dspring.datasource.*}); recusa qualquer base
 *   cujo nome não contenha {@code test}. Nunca na de produção.</li>
 *   <li>Prepara o schema (Flyway) antes de inserir, por isso uma base acabada de criar serve.</li>
 *   <li>Tudo o que criam tem o domínio {@link #DOMINIO} nos emails das empresas e dos utilizadores: se uma execução morrer
 *   sem limpar, a seguinte apaga o resto (em cascata, inclusive o que os testes sem rollback tenham criado a partir deles).</li>
 * </ul>
 *
 * Os testes que correm em {@code @Transactional} alteram tudo isto à vontade: o rollback repõe.
 */
public final class Contas {

    /** Domínio reservado (RFC 2606): nunca é um email real. Marca tudo o que estes testes criam. */
    public static final String DOMINIO = "contas-temporarias.invalid";

    /** Papel de cada utilizador temporário e a empresa a que pertence (cliente, fornecedora ou nenhuma). */
    public enum Persona {
        COMPRADOR("COMPRADOR", true),
        COMPANY_ADMIN("COMPANY_ADMIN", true),
        FINANCEIRO("FINANCEIRO", true),
        FORNECEDOR("FORNECEDOR", false),
        ADMIN_SISTEMA("ADMIN_SISTEMA", null);

        final String papel;
        /** true = empresa cliente; false = empresa fornecedora; null = sem empresa. */
        final Boolean daCliente;

        Persona(String papel, Boolean daCliente) {
            this.papel = papel;
            this.daCliente = daCliente;
        }
    }

    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final String SENHA = senhaAleatoria();
    private static final Map<Persona, String> EMAILS = new EnumMap<>(Persona.class);
    private static boolean iniciado = false;

    private Contas() {
    }

    /** A senha (aleatória, gerada nesta execução) de TODOS os utilizadores temporários. */
    public static String senha() {
        return SENHA;
    }

    /** Email do utilizador temporário desta persona; cria o cenário na primeira utilização. */
    public static synchronized String email(Persona persona) {
        garantir();
        return EMAILS.get(persona);
    }

    /** Nome do utilizador temporário desta persona (o que a aplicação devolve como nome do autor). */
    public static String nome(Persona persona) {
        return "Utilizador temporário (" + persona.name() + ")";
    }

    /** Email novo e único para um utilizador que o próprio teste vai criar (convite, registo, fixture…). */
    public static String emailUnico(String prefixo) {
        return prefixo + "." + sufixo() + "@" + DOMINIO;
    }

    /** NIF novo e único para uma empresa que o próprio teste vai criar. */
    public static String nifUnico() {
        return "AO-T-" + sufixo().toUpperCase();
    }

    /** Senha nova, aleatória e que cumpre a política de senhas, para um teste que a defina ou altere. */
    public static String senhaNova() {
        return senhaAleatoria();
    }

    /** Id da empresa do utilizador temporário (a cliente ou a fornecedora). */
    public static String idDaEmpresa(Persona persona) {
        return consulta("SELECT company_id FROM users WHERE email = ?", email(persona));
    }

    /** Nome da empresa do utilizador temporário. */
    public static String nomeDaEmpresa(Persona persona) {
        return consulta("SELECT c.name FROM companies c JOIN users u ON u.company_id = c.id WHERE u.email = ?", email(persona));
    }

    /** NIF da empresa do utilizador temporário. */
    public static String nifDaEmpresa(Persona persona) {
        return consulta("SELECT c.tax_id FROM companies c JOIN users u ON u.company_id = c.id WHERE u.email = ?", email(persona));
    }

    private static String consulta(String sql, String parametro) {
        try (Connection c = ligar(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, parametro);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- criação e limpeza ------------------------------------------------------------------------------------------

    private static void garantir() {
        if (iniciado) return;
        migrar();
        try (Connection c = ligar()) {
            c.setAutoCommit(true);
            purgar(c); // restos de uma execução anterior que não chegou a limpar
            String[] empresas = Dados.criarEmpresas(c); // {cliente, fornecedora}
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4); // só testes: custo mínimo
            String hash = encoder.encode(SENHA);
            Map<Persona, String> ids = new EnumMap<>(Persona.class);
            for (Persona p : Persona.values()) {
                String empresa = p.daCliente == null ? null : (p.daCliente ? empresas[0] : empresas[1]);
                String email = emailUnico(p.name().toLowerCase().replace('_', '-'));
                String id = UUID.randomUUID().toString();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO users (id, name, email, password_hash, role, company_id, active, terms_accepted_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?::\"PersonaRole\", ?, true, now(), now(), now())")) {
                    ps.setString(1, id);
                    ps.setString(2, nome(p));
                    ps.setString(3, email);
                    ps.setString(4, hash);
                    ps.setString(5, p.papel);
                    ps.setString(6, empresa);
                    ps.executeUpdate();
                }
                EMAILS.put(p, email);
                ids.put(p, id);
            }
            Dados.criarCenario(c, ids);
        } catch (SQLException e) {
            // Não deixar a meio: o que se criou apaga-se, e o erro explica-se.
            limparNoFim();
            throw new IllegalStateException("Não foi possível criar o cenário de testes na base de testes: " + e.getMessage(), e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(Contas::limparNoFim, "contas-temporarias-limpeza"));
        iniciado = true;
    }

    /**
     * As constantes dos testes pedem os utilizadores quando a classe carrega — antes de o Spring (e o Flyway do perfil de
     * testes) preparar a base. Numa base acabada de criar ainda não há tabelas: migra-se aqui, com a mesma configuração
     * da aplicação (base existente sem histórico = V1; só correm as migrações seguintes). Idempotente.
     */
    private static void migrar() {
        Properties yaml = lerPerfilDeTestes();
        String url = valor("spring.datasource.url", yaml);
        validarBaseDeTestes(url);
        Flyway.configure().dataSource(url, valor("spring.datasource.username", yaml), valor("spring.datasource.password", yaml))
                .baselineOnMigrate(true).baselineVersion("1").load().migrate();
    }

    private static void limparNoFim() {
        try (Connection c = ligar()) {
            c.setAutoCommit(true);
            purgar(c);
            Dados.apagarGlobais(c);
        } catch (SQLException e) {
            System.err.println("[Contas] Não foi possível apagar os dados temporários: " + e.getMessage()
                    + " — a próxima execução apaga-os (domínio " + DOMINIO + ").");
        }
    }

    /**
     * Apaga as empresas e os utilizadores com o domínio reservado (desta execução ou de uma anterior) e TUDO o que depende
     * deles — catálogo, ordens, faturas, pedidos de suporte, o que os testes sem rollback tenham criado…
     */
    private static void purgar(Connection c) throws SQLException {
        String[] empresas = ids(c, "SELECT id FROM companies WHERE contact_email LIKE '%@" + DOMINIO + "'");
        String[] utilizadores = ids(c, "SELECT id FROM users WHERE email LIKE '%@" + DOMINIO + "' OR company_id = ANY (ARRAY(SELECT id FROM companies WHERE contact_email LIKE '%@" + DOMINIO + "'))");
        // Linhas ligadas por colunas SEM chave estrangeira (a cascata não as vê): faturas (por ordem ou contrato) e pedidos de suporte.
        if (empresas.length > 0) {
            Connection cx = c;
            String[] faturas = ids(cx, "SELECT id FROM invoices WHERE purchase_order_id IN (SELECT id FROM purchase_orders WHERE buyer_company_id = ANY (?) OR supplier_company_id = ANY (?)) "
                    + "OR contract_id IN (SELECT id FROM contracts WHERE client_company_id = ANY (?) OR supplier_company_id = ANY (?))", empresas, empresas, empresas, empresas);
            apagarEmCascata(c, "invoices", "id", faturas, 0);
        }
        // Notificações dos utilizadores/empresas temporários: a base só poria a NULL a referência e elas ficariam órfãs.
        if (utilizadores.length > 0 || empresas.length > 0) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM notifications WHERE user_id = ANY (?) OR company_id = ANY (?)")) {
                ps.setArray(1, c.createArrayOf("text", utilizadores));
                ps.setArray(2, c.createArrayOf("text", empresas));
                ps.executeUpdate();
            }
        }
        if (utilizadores.length > 0) {
            apagarEmCascata(c, "support_tickets", "id", ids(c, "SELECT id FROM support_tickets WHERE user_id = ANY (?)", utilizadores), 0);
            apagarVarios(c, "DELETE FROM audit_logs WHERE actor_id = ANY (?)", utilizadores);
            apagarEmCascata(c, "users", "id", utilizadores, 0);
        }
        if (empresas.length > 0) apagarEmCascata(c, "companies", "id", empresas, 0);
    }

    /**
     * Apaga linhas de {@code tabela} (onde {@code coluna} está em {@code valores}) depois de apagar, recursivamente, as que
     * as referenciam por chaves estrangeiras RESTRICT/NO ACTION (as CASCADE e SET NULL trata-as a própria base).
     */
    private static void apagarEmCascata(Connection c, String tabela, String coluna, String[] valores, int profundidade) throws SQLException {
        if (valores.length == 0 || profundidade > 10) return;
        String[] chaves = temId(c, tabela) ? ids(c, "SELECT id FROM " + tabela + " WHERE " + coluna + " = ANY (?)", valores) : new String[0];
        if (chaves.length > 0) {
            Set<String> filhos = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT k.conrelid::regclass::text, a.attname FROM pg_constraint k "
                            + "JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = k.conkey[1] "
                            + "WHERE k.contype = 'f' AND k.confrelid = ?::regclass AND array_length(k.conkey, 1) = 1 AND k.confdeltype IN ('a', 'r')")) {
                ps.setString(1, tabela);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) filhos.add(rs.getString(1) + "|" + rs.getString(2));
                }
            }
            for (String f : filhos) {
                String[] t = f.split("\\|");
                if (t[0].equals(tabela)) continue; // auto-referência: apaga-se na mesma instrução
                apagarEmCascata(c, t[0], t[1], chaves, profundidade + 1);
            }
        }
        apagarVarios(c, "DELETE FROM " + tabela + " WHERE " + coluna + " = ANY (?)", valores);
    }

    private static boolean temId(Connection c, String tabela) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = 'id'")) {
            ps.setString(1, tabela.replace("public.", "").replace("\"", ""));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /** Cada argumento é um array de textos ligado, por ordem, a um «?» da consulta (que usa {@code = ANY (?)}). */
    private static String[] ids(Connection c, String sql, String[]... arrays) throws SQLException {
        List<String> r = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < arrays.length; i++) ps.setArray(i + 1, c.createArrayOf("text", arrays[i]));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) r.add(rs.getString(1));
            }
        }
        return r.toArray(new String[0]);
    }

    private static void apagarVarios(Connection c, String sql, String[] ids) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", ids));
            ps.executeUpdate();
        }
    }

    // --- ligação à base de testes (a do perfil `test`; nunca outra) -------------------------------------------------

    private static Connection ligar() throws SQLException {
        Properties yaml = lerPerfilDeTestes();
        String url = valor("spring.datasource.url", yaml);
        validarBaseDeTestes(url);
        return DriverManager.getConnection(url, valor("spring.datasource.username", yaml), valor("spring.datasource.password", yaml));
    }

    /** Salvaguarda: estes testes criam e apagam dados — só correm numa base cujo nome diga que é de testes. */
    private static void validarBaseDeTestes(String url) {
        if (url == null || !url.contains("/")) throw new IllegalStateException("Perfil de testes sem spring.datasource.url.");
        String base = url.substring(url.lastIndexOf('/') + 1).split("\\?")[0];
        if (!base.toLowerCase().contains("test")) {
            throw new IllegalStateException("A base '" + base + "' não parece uma base de testes (o nome tem de conter \"test\"). "
                    + "Os testes não criam dados temporários noutras bases.");
        }
    }

    private static String valor(String chave, Properties yaml) {
        String sistema = System.getProperty(chave);
        return sistema != null && !sistema.isBlank() ? sistema : yaml.getProperty(chave);
    }

    private static Properties lerPerfilDeTestes() {
        YamlPropertiesFactoryBean f = new YamlPropertiesFactoryBean();
        f.setResources(new ClassPathResource("application-test.yml"));
        Properties p = f.getObject();
        return p == null ? new Properties() : p;
    }

    // --- aleatoriedade -----------------------------------------------------------------------------------------------

    private static String sufixo() {
        return Long.toString(Math.abs(ALEATORIO.nextLong()), 36);
    }

    /** 22 caracteres com maiúsculas, minúsculas, dígitos e símbolo; nunca uma sequência nem uma senha comum. */
    private static String senhaAleatoria() {
        String maiusculas = "ABCDEFGHJKLMNPQRSTUVWXYZ", minusculas = "abcdefghijkmnpqrstuvwxyz", digitos = "23456789", simbolos = "!#$%&*+-=?@^_";
        String todos = maiusculas + minusculas + digitos + simbolos;
        StringBuilder sb = new StringBuilder();
        sb.append(maiusculas.charAt(ALEATORIO.nextInt(maiusculas.length())));
        sb.append(minusculas.charAt(ALEATORIO.nextInt(minusculas.length())));
        sb.append(digitos.charAt(ALEATORIO.nextInt(digitos.length())));
        sb.append(simbolos.charAt(ALEATORIO.nextInt(simbolos.length())));
        while (sb.length() < 22) sb.append(todos.charAt(ALEATORIO.nextInt(todos.length())));
        return sb.toString();
    }
}
