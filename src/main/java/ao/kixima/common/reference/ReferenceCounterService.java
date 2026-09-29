package ao.kixima.common.reference;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Year;
import java.util.Map;

/**
 * Espelha backend/src/utils/reference.js — referências legíveis
 * (PO-2026-000123) com contagem ATÓMICA por prefixo+ano, incrementada pela
 * própria base de dados (INSERT ... ON CONFLICT DO UPDATE ... RETURNING),
 * nunca por um COUNT/leitura-e-escrita em duas instruções.
 *
 * Identificadores de tabela/coluna vêm de {@link #TABELA_DO_MODELO}, uma
 * lista fechada — nunca interpolados a partir de input externo (mesma
 * cautela do Node contra SQL injection).
 */
@Service
public class ReferenceCounterService {

    private record Alvo(String tabela, String coluna) {
    }

    private static final Map<String, Alvo> TABELA_DO_MODELO = Map.of(
            "purchaseOrder", new Alvo("purchase_orders", "reference"),
            "invoice", new Alvo("invoices", "reference"),
            "contract", new Alvo("contracts", "reference"),
            "supplierDevRequest", new Alvo("supplier_dev_requests", "reference"),
            "planoCobranca", new Alvo("plano_cobrancas", "referencia"),
            "creditNote", new Alvo("credit_notes", "reference"),
            "addonCobranca", new Alvo("addon_cobrancas", "referencia")
    );

    private final JdbcTemplate jdbcTemplate;

    public ReferenceCounterService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** O maior número já emitido com este prefixo e ano — calculado PELA BASE, um único valor de volta. */
    private long seedValue(String prefix, String counterModel, int year) {
        Alvo alvo = TABELA_DO_MODELO.get(counterModel);
        if (alvo == null) {
            throw new IllegalArgumentException(
                    "Modelo \"" + counterModel + "\" não está registado em TABELA_DO_MODELO. Acrescente-o antes de gerar referências para ele.");
        }
        String sql = "SELECT COALESCE(MAX(NULLIF(regexp_replace(\"" + alvo.coluna() + "\", '^.*-', ''), '')::bigint), 0) "
                + "FROM \"" + alvo.tabela() + "\" WHERE \"" + alvo.coluna() + "\" LIKE ? AND \"" + alvo.coluna() + "\" ~ '-[0-9]+$'";
        Long max = jdbcTemplate.queryForObject(sql, Long.class, prefix + "-" + year + "-%");
        return max == null ? 0 : max;
    }

    /**
     * @param campo Nome do campo que guarda a referência (`reference` nos
     *              modelos antigos, `referencia` nos novos) — não usado
     *              directamente aqui (a coluna já vem de TABELA_DO_MODELO),
     *              mantido como parâmetro para espelhar a assinatura do Node
     *              e o mesmo aviso: ler o campo errado arrancaria do 1.
     */
    public String nextReference(String prefix, String counterModel) {
        int year = Year.now().getValue();
        String key = prefix + "-" + year;
        long inicial = seedValue(prefix, counterModel, year) + 1;

        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO "reference_counters" ("key", "value")
                VALUES (?, ?)
                ON CONFLICT ("key") DO UPDATE SET "value" = "reference_counters"."value" + 1
                RETURNING "value"
                """, Long.class, key, inicial);

        return "%s-%d-%06d".formatted(prefix, year, value);
    }
}
