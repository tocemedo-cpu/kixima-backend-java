package ao.kixima.security;

import ao.kixima.company.CompanyStatus;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Portão de aprovação do cadastro. Uma conta de uma empresa que ainda não está APROVADA (cadastro em RASCUNHO, em análise
 * ou REJEITADO) pode entrar, mas só chega ao que serve para completar, acompanhar ou corrigir o cadastro: a sessão
 * ({@code /api/auth/…}) e {@code /api/onboarding/…}. TODO o resto — catálogo, ordens, faturas, utilizadores da empresa… —
 * responde 403 {@code COMPANY_NOT_APPROVED}. A regra vive no {@link AuthenticationFilter}, antes de qualquer controller, por
 * isso um endpoint novo fica protegido por omissão e chamar o endpoint directamente não contorna nada.
 */
public final class CadastroGate {

    public static final String CODIGO = "COMPANY_NOT_APPROVED";

    private static final List<Pattern> PERMITIDOS = List.of(
            Pattern.compile("^/api/auth/"),
            Pattern.compile("^/api/onboarding(/|$)")
    );

    private CadastroGate() {
    }

    /** Sem empresa (Admin do Sistema) ou com a empresa aprovada, nada se restringe. */
    public static boolean restringe(CompanyStatus estado) {
        return estado != null && estado != CompanyStatus.APROVADA;
    }

    public static boolean permite(CompanyStatus estado, String caminho) {
        return !restringe(estado) || PERMITIDOS.stream().anyMatch(p -> p.matcher(caminho).find());
    }

    public static String mensagem(CompanyStatus estado) {
        return switch (estado) {
            case RASCUNHO -> "Complete e submeta o cadastro da empresa para aceder a esta funcionalidade.";
            case PENDENTE -> "O cadastro da empresa está em análise pela KIXIMA. Esta funcionalidade fica disponível após a aprovação.";
            case REJEITADA -> "O cadastro da empresa foi rejeitado. Corrija os dados e volte a submetê-lo para aceder a esta funcionalidade.";
            case SUSPENSA -> "A empresa está suspensa. Fale com a KIXIMA para voltar a aceder a esta funcionalidade.";
            case APROVADA -> "";
        };
    }
}
