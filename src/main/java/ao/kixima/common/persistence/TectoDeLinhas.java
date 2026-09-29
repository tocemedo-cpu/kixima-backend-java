package ao.kixima.common.persistence;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Espelha o tecto por omissão nas leituras em lista de
 * backend/src/config/database.js ({@code TETO_POR_OMISSAO}/{@code DB_MAX_ROWS}).
 *
 * O PROBLEMA: dezenas de leituras em lista sem limite. Com dez empresas não se
 * nota; com duzentas e catálogos grandes, uma listagem carrega tudo para
 * memória e mata o processo — para toda a gente. O contentor tem 512 MB.
 *
 * A ARMADILHA DA SOLUÇÃO ÓBVIA: um limite cego troca um crash por uma coisa
 * pior — resultados truncados em silêncio, e nove serviços somam valores sobre
 * estas leituras.
 *
 * A RESOLUÇÃO (a mesma do Node): o tecto é alto — é uma rede de segurança, não
 * paginação — e, quando chega a ser atingido, GRITA (ver
 * {@link TectoDeLinhasAspect}). Quem precisa mesmo de tudo passa um
 * {@code Pageable}/{@code Limit} explícito e fica responsável por ele.
 *
 * COMO SE APLICA: no Node é uma extensão do Prisma à volta de {@code findMany}.
 * Aqui o equivalente de {@code findMany} é uma chamada a um repositório Spring
 * Data — o {@link TectoDeLinhasAspect} abre um âmbito por thread durante essa
 * chamada, e o {@link TectoDeLinhasStatementInspector} acrescenta
 * {@code limit N} ao SQL que o Hibernate executa DENTRO desse âmbito, quando
 * a instrução ainda não traz paginação própria. Fora do âmbito (relações
 * lazy carregadas depois, como os {@code include} do Prisma, que a extensão
 * do Node também não apanha) e fora do Hibernate ({@code JdbcTemplate}, o
 * {@code $queryRaw} do Node) nada é tocado.
 */
public final class TectoDeLinhas {

    /** `Number(process.env.DB_MAX_ROWS) || 1000` — a omissão vive em application.yml (kixima.db.max-rows). */
    public static final int POR_OMISSAO = 1000;

    /**
     * Tabelas onde o tecto NÃO se aplica — {@code SEM_TECTO = ['referenceCounter']}
     * no Node: leituras pequenas por natureza e usadas em contas que têm de
     * estar certas. Ficam aqui, à vista, e não espalhadas.
     */
    public static final Set<String> TABELAS_SEM_TECTO = Set.of("reference_counters");

    private static final ThreadLocal<int[]> AMBITO = ThreadLocal.withInitial(() -> new int[1]);

    private static final Pattern SO_CONTAGEM = Pattern.compile("^\\s*select\\s+count\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOQUEIO = Pattern.compile("for\\s+(no\\s+key\\s+update|key\\s+share|update|share)\\b");

    private TectoDeLinhas() {
    }

    /** `Number(process.env.DB_MAX_ROWS) || 1000` — zero ou negativo volta à omissão, nunca a um `limit 0`. */
    public static int efectivo(int configurado) {
        return configurado > 0 ? configurado : POR_OMISSAO;
    }

    // --- Âmbito por thread ("estamos dentro de uma chamada a um repositório") ------

    static void entrar() {
        AMBITO.get()[0]++;
    }

    static void sair() {
        int[] profundidade = AMBITO.get();
        if (profundidade[0] > 0) profundidade[0]--;
        if (profundidade[0] == 0) AMBITO.remove();
    }

    static boolean dentroDeRepositorio() {
        return AMBITO.get()[0] > 0;
    }

    // --- Reescrita do SQL ------------------------------------------------------------

    /**
     * Acrescenta {@code limit tecto} a um SELECT que ainda não traz
     * {@code limit}/{@code offset}/{@code fetch first} ao nível de topo,
     * antes de um {@code for update}/{@code for share} final se existir.
     * Devolve o SQL intacto quando não é um SELECT, quando é só uma contagem,
     * quando não lê tabela nenhuma ({@code select nextval(...)}) ou quando lê
     * uma tabela de {@link #TABELAS_SEM_TECTO}.
     */
    public static String aplicar(String sql, int tecto) {
        if (sql == null) return null;
        // Carácter a carácter, para as posições coincidirem com as do SQL original
        // (String.toLowerCase pode mudar o comprimento com certos caracteres Unicode).
        char[] chars = sql.toCharArray();
        for (int i = 0; i < chars.length; i++) chars[i] = Character.toLowerCase(chars[i]);
        String minusculas = new String(chars);
        String inicio = minusculas.stripLeading();
        if (!(inicio.startsWith("select") || inicio.startsWith("with"))) return sql;
        if (SO_CONTAGEM.matcher(minusculas).find()) return sql;
        for (String tabela : TABELAS_SEM_TECTO) {
            if (minusculas.contains(tabela)) return sql;
        }

        int profundidade = 0;
        boolean emTexto = false;
        boolean emIdentificador = false;
        boolean temFrom = false;
        int posBloqueio = -1;
        int n = minusculas.length();
        for (int i = 0; i < n; i++) {
            char c = minusculas.charAt(i);
            if (emTexto) {
                if (c == '\'') emTexto = false;
                continue;
            }
            if (emIdentificador) {
                if (c == '"') emIdentificador = false;
                continue;
            }
            if (c == '\'') {
                emTexto = true;
            } else if (c == '"') {
                emIdentificador = true;
            } else if (c == '(') {
                profundidade++;
            } else if (c == ')') {
                profundidade--;
            } else if (profundidade == 0 && inicioDePalavra(minusculas, i)) {
                if (palavra(minusculas, i, "from")) {
                    temFrom = true;
                } else if (palavra(minusculas, i, "limit") || palavra(minusculas, i, "offset") || palavra(minusculas, i, "fetch")) {
                    return sql; // já vem paginado — o chamador assumiu o limite
                } else if (posBloqueio < 0 && palavra(minusculas, i, "for")) {
                    Matcher m = BLOQUEIO.matcher(minusculas);
                    m.region(i, n);
                    if (m.lookingAt()) posBloqueio = i;
                }
            }
        }
        if (!temFrom) return sql;

        String fim = sql.stripTrailing();
        if (fim.endsWith(";")) fim = fim.substring(0, fim.length() - 1).stripTrailing();
        if (posBloqueio >= 0) {
            return sql.substring(0, posBloqueio).stripTrailing() + " limit " + tecto + " " + sql.substring(posBloqueio);
        }
        return fim + " limit " + tecto;
    }

    private static boolean inicioDePalavra(String s, int i) {
        return i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)) && s.charAt(i - 1) != '_';
    }

    private static boolean palavra(String s, int i, String p) {
        if (!s.startsWith(p, i)) return false;
        int fim = i + p.length();
        return fim >= s.length() || !Character.isLetterOrDigit(s.charAt(fim)) && s.charAt(fim) != '_';
    }
}
