package ao.kixima.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Espelha backend/src/utils/adminAreas.js — as áreas em que o poder de
 * ADMIN_SISTEMA se pode dividir. `adminAreas` vazio no utilizador continua a
 * significar Super Admin (ver {@link ao.kixima.user.User#getAdminAreas()}).
 * FATURACAO fica separada de FINANCEIRO de propósito (implicações fiscais
 * directas — AGT/SAF-T), tal como no Node.
 */
public final class AdminArea {

    public static final String CADASTRO = "cadastro";
    public static final String FINANCEIRO = "financeiro";
    public static final String FATURACAO = "faturacao";
    public static final String APOLICES = "apolices";
    public static final String SUPORTE = "suporte";
    public static final String OPERACOES = "operacoes";

    public static final List<String> AREAS_ADMIN = List.of(
            CADASTRO, FINANCEIRO, FATURACAO, APOLICES, SUPORTE, OPERACOES);

    public static final Map<String, String> AREAS_ADMIN_LABEL;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(CADASTRO, "Cadastro & Empresas");
        m.put(FINANCEIRO, "Financeiro");
        m.put(FATURACAO, "Faturação (AGT)");
        m.put(APOLICES, "Apólices");
        m.put(SUPORTE, "Suporte");
        m.put(OPERACOES, "Operação da Plataforma");
        AREAS_ADMIN_LABEL = Map.copyOf(m);
    }

    private AdminArea() {
    }
}
