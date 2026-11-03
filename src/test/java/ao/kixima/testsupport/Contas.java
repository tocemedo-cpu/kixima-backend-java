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
 * Utilizadores dos testes, criados DURANTE a execução e removidos no fim. Nenhum email nem senha está escrito no
 * código, nas migrações, nos seeds ou na configuração: cada execução gera os seus (emails com sufixo aleatório e uma
 * senha aleatória), insere-os na base de testes, e um gancho de saída da JVM apaga-os.
 *
 * <ul>
 *   <li>Os utilizadores ficam ligados às empresas de dados de exemplo da base de testes (por NIF) — a base de testes
 *   continua a ter de ter esses dados de negócio, mas já não precisa de ter NENHUM utilizador. Se uma dessas empresas
 *   não existir, cria-se uma empresa temporária (e apaga-se no fim): os testes que só precisam de contas funcionam
 *   numa base acabada de criar pelo Flyway.</li>
 *   <li>Só tocam na base de testes (a mesma ligação do perfil {@code test}, ou {@code -Dspring.datasource.*}); nunca
 *   na de produção.</li>
 *   <li>Tudo o que criam tem o domínio {@link #DOMINIO}: se uma execução morrer sem limpar, a seguinte apaga o resto.</li>
 * </ul>
 *
 * Os testes que correm em {@code @Transactional} nem precisam de limpar nada do que criam a mais (rollback); o que
 * este ficheiro cria, fora da transacção do teste, remove-se aqui.
 */
public final class Contas {

    /** Domínio reservado (RFC 2606): nunca é um email real. Marca tudo o que estes testes criam. */
    public static final String DOMINIO = "contas-temporarias.invalid";
    private static final String NIF_TEMPORARIO = "TMP-";

    /** Papel e empresa (por NIF dos dados de exemplo) de cada utilizador temporário. */
    public enum Persona {
        COMPRADOR("COMPRADOR", NIF_CLIENTE()),
        COMPANY_ADMIN("COMPANY_ADMIN", NIF_CLIENTE()),
        FINANCEIRO("FINANCEIRO", NIF_CLIENTE()),
        FORNECEDOR("FORNECEDOR", NIF_FORNECEDOR()),
        ADMIN_SISTEMA("ADMIN_SISTEMA", null);

        final String papel;
        final String nifDaEmpresa;

        Persona(String papel, String nifDaEmpresa) {
            this.papel = papel;
            this.nifDaEmpresa = nifDaEmpresa;
        }

        private static String NIF_CLIENTE() {
            return "AO-CLI-0001";
        }

        private static String NIF_FORNECEDOR() {
            return "AO-FOR-0001";
        }
    }

    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final String SENHA = senhaAleatoria();
    private static final Map<Persona, String> EMAILS = new EnumMap<>(Persona.class);
    private static final List<String> EMPRESAS_TEMPORARIAS = new ArrayList<>();
    private static boolean iniciado = false;

    private Contas() {
    }

    /** A senha (aleatória, gerada nesta execução) de TODOS os utilizadores temporários. */
    public static String senha() {
        return SENHA;
    }

    /** Email do utilizador temporário desta persona; cria-o na primeira utilização. */
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

    // --- criação e limpeza ------------------------------------------------------------------------------------------

    private static void garantir() {
        if (iniciado) return;
        migrar();
        try (Connection c = ligar()) {
            c.setAutoCommit(true);
            purgar(c); // restos de uma execução anterior que não chegou a limpar
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4); // só testes: custo mínimo
            String hash = encoder.encode(SENHA);
            for (Persona p : Persona.values()) {
                String empresa = p.nifDaEmpresa == null ? null : idDaEmpresa(c, p.nifDaEmpresa, p);
                String email = emailUnico(p.name().toLowerCase().replace('_', '-'));
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO users (id, name, email, password_hash, role, company_id, active, terms_accepted_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?::\"PersonaRole\", ?, true, now(), now(), now())")) {
                    ps.setString(1, UUID.randomUUID().toString());
                    ps.setString(2, nome(p));
                    ps.setString(3, email);
                    ps.setString(4, hash);
                    ps.setString(5, p.papel);
                    ps.setString(6, empresa);
                    ps.executeUpdate();
                }
                EMAILS.put(p, email);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Não foi possível criar os utilizadores temporários na base de testes: " + e.getMessage(), e);
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
        String user = valor("spring.datasource.username", yaml);
        String senha = valor("spring.datasource.password", yaml);
        validarBaseDeTestes(url);
        Flyway.configure().dataSource(url, user, senha).baselineOnMigrate(true).baselineVersion("1").load().migrate();
    }

    /** Nome da empresa do utilizador temporário (as empresas de dados de exemplo, ou a temporária). */
    public static String nomeDaEmpresa(Persona persona) {
        String email = email(persona);
        try (Connection c = ligar(); PreparedStatement ps = c.prepareStatement(
                "SELECT c.name FROM companies c JOIN users u ON u.company_id = c.id WHERE u.email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** NIF da empresa do utilizador temporário. */
    public static String nifDaEmpresa(Persona persona) {
        String email = email(persona);
        try (Connection c = ligar(); PreparedStatement ps = c.prepareStatement(
                "SELECT c.tax_id FROM companies c JOIN users u ON u.company_id = c.id WHERE u.email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String idDaEmpresa(Connection c, String nif, Persona p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM companies WHERE tax_id = ?")) {
            ps.setString(1, nif);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        // A base não tem a empresa dos dados de exemplo: cria-se uma temporária (apagada no fim).
        String id = UUID.randomUUID().toString();
        String tipo = p == Persona.FORNECEDOR ? "FORNECEDOR" : "CLIENTE";
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO companies (id, name, tax_id, type, status, contact_email, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?::\"CompanyType\", 'APROVADA'::\"CompanyStatus\", ?, now(), now())")) {
            ps.setString(1, id);
            ps.setString(2, "Empresa temporária " + tipo.toLowerCase());
            ps.setString(3, NIF_TEMPORARIO + sufixo().toUpperCase());
            ps.setString(4, tipo);
            ps.setString(5, emailUnico("contacto"));
            ps.executeUpdate();
        }
        EMPRESAS_TEMPORARIAS.add(id);
        return id;
    }

    private static void limparNoFim() {
        try (Connection c = ligar()) {
            c.setAutoCommit(true);
            purgar(c);
            for (String id : EMPRESAS_TEMPORARIAS) apagar(c, "DELETE FROM companies WHERE id = ?", id);
        } catch (SQLException e) {
            System.err.println("[Contas] Não foi possível apagar os utilizadores temporários: " + e.getMessage()
                    + " — a próxima execução apaga-os (domínio " + DOMINIO + ").");
        }
    }

    /** Apaga todos os utilizadores com o domínio reservado (desta execução ou de uma anterior) e o que os referencia. */
    private static void purgar(Connection c) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id FROM users WHERE email LIKE '%@" + DOMINIO + "'")) {
            while (rs.next()) ids.add(rs.getString(1));
        }
        if (ids.isEmpty()) return;
        String[] idsArr = ids.toArray(new String[0]);
        // Linhas que ficaram a referenciar estes utilizadores (RESTRICT/NO ACTION) — de testes sem rollback.
        Set<String> tabelasFk = new HashSet<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT conrelid::regclass::text, (SELECT attname FROM pg_attribute WHERE attrelid = conrelid AND attnum = conkey[1]) "
                        + "FROM pg_constraint WHERE contype = 'f' AND confrelid = 'users'::regclass AND confdeltype IN ('a', 'r')")) {
            while (rs.next()) tabelasFk.add(rs.getString(1) + "|" + rs.getString(2));
        }
        for (String par : tabelasFk) {
            String[] t = par.split("\\|");
            apagarVarios(c, "DELETE FROM " + t[0] + " WHERE " + t[1] + " = ANY (?)", idsArr);
        }
        apagarVarios(c, "DELETE FROM audit_logs WHERE actor_id = ANY (?)", idsArr);
        apagarVarios(c, "DELETE FROM users WHERE id = ANY (?)", idsArr);
    }

    private static void apagar(Connection c, String sql, String id) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException ignorado) {
            // uma empresa temporária ainda referenciada fica para a próxima limpeza por domínio de email
        }
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
        String user = valor("spring.datasource.username", yaml);
        String senha = valor("spring.datasource.password", yaml);
        validarBaseDeTestes(url);
        return DriverManager.getConnection(url, user, senha);
    }

    /** Salvaguarda: estes testes criam e apagam contas — só correm numa base cujo nome diga que é de testes. */
    private static void validarBaseDeTestes(String url) {
        if (url == null || !url.contains("/")) throw new IllegalStateException("Perfil de testes sem spring.datasource.url.");
        String base = url.substring(url.lastIndexOf('/') + 1).split("\\?")[0];
        if (!base.toLowerCase().contains("test")) {
            throw new IllegalStateException("A base '" + base + "' não parece uma base de testes (o nome tem de conter \"test\"). "
                    + "Os testes não criam utilizadores temporários noutras bases.");
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
