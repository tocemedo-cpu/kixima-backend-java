package ao.kixima.common.error;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Acumula erros por campo e lança-os com a MESMA forma que o middleware
 * {@code validate()} do Node (utils/validate.js) produz a partir do zod:
 * 422 {@code VALIDATION_ERROR}, mensagem "Dados inválidos." e
 * {@code details = result.error.flatten()} — {@code {formErrors: [], fieldErrors: {campo: [msg, ...]}}}.
 *
 * Os textos aqui são os que o zod 3.23 emite por omissão, letra por letra,
 * para que {@code fieldErrors} seja igual nos dois backends. O zod NÃO pára
 * no primeiro campo inválido: reporta todos, pela ordem do schema — daí
 * acumular em vez de lançar logo.
 */
public final class ErrosDeCampos {

    /** Campo obrigatório ausente (zod: {@code invalid_type} com {@code received: undefined}). */
    public static final String REQUIRED = "Required";

    /**
     * O regex de {@code z.string().email()} do zod 3.23, tal e qual — a
     * validação "simples" de email que o Java tinha aceitava/recusava
     * endereços de forma diferente nos casos limite.
     */
    public static final Pattern EMAIL = Pattern.compile(
            "^(?!\\.)(?!.*\\.\\.)([A-Z0-9_'+\\-.]*)[A-Z0-9_+-]@([A-Z0-9][A-Z0-9\\-]*\\.)+[A-Z]{2,}$",
            Pattern.CASE_INSENSITIVE);

    private final Map<String, List<String>> fieldErrors = new LinkedHashMap<>();

    /** {@code z.string().max(n)} sem mensagem própria. */
    public static String maximo(int n) {
        return "String must contain at most " + n + " character(s)";
    }

    /** {@code z.enum([...])} com um valor fora da lista (uma string). */
    public static String enumInvalido(List<String> opcoes, String recebido) {
        return "Invalid enum value. Expected " + opcoesFormatadas(opcoes) + ", received '" + recebido + "'";
    }

    private static String opcoesFormatadas(List<String> opcoes) {
        return String.join(" | ", opcoes.stream().map(o -> "'" + o + "'").toList());
    }

    public ErrosDeCampos adicionar(String campo, String mensagem) {
        fieldErrors.computeIfAbsent(campo, k -> new ArrayList<>()).add(mensagem);
        return this;
    }

    /**
     * {@code z.string().max(max).optional()} — só valida quando o campo veio;
     * "" conta como string vazia (válida), tal como no zod.
     */
    public ErrosDeCampos textoOpcionalAte(String campo, String valor, int max) {
        if (valor != null && valor.length() > max) adicionar(campo, maximo(max));
        return this;
    }

    /** {@code z.enum(opcoes).optional()}. */
    public ErrosDeCampos enumOpcional(String campo, String valor, List<String> opcoes) {
        if (valor != null && !opcoes.contains(valor)) adicionar(campo, enumInvalido(opcoes, valor));
        return this;
    }

    public boolean vazio() {
        return fieldErrors.isEmpty();
    }

    /** Lança a ValidationException com o {@code flatten()} do zod quando há pelo menos um erro. */
    public void lancarSeHouver() {
        if (fieldErrors.isEmpty()) return;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("formErrors", List.of());
        details.put("fieldErrors", fieldErrors);
        throw new ValidationException("Dados inválidos.", details);
    }
}
