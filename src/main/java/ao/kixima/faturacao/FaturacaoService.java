package ao.kixima.faturacao;

import ao.kixima.po.PurchaseOrderItem;
import ao.kixima.tax.TaxService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Espelha backend/src/services/faturacaoService.js — numeração certificada e
 * cadeia de integridade das faturas (base AGT). A série é POR FORNECEDOR
 * (Company.serieFiscal), nunca global.
 *
 * `atribuir()` usa JdbcTemplate com SQL bruto, tal como o Node usa
 * `$queryRaw`/`$executeRaw` — o {@code SELECT ... FOR UPDATE} é o mecanismo
 * inteiro (serializa emissões concorrentes na mesma série); traduzi-lo para
 * JPQL/Criteria perderia exactamente essa garantia.
 */
@Service
public class FaturacaoService {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CARIMBO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbcTemplate;
    private final TaxService taxService;

    public FaturacaoService(JdbcTemplate jdbcTemplate, TaxService taxService) {
        this.jdbcTemplate = jdbcTemplate;
        this.taxService = taxService;
    }

    public record LinhaFatura(String productCode, String description, BigDecimal quantity,
                               BigDecimal unitPrice, BigDecimal netAmount, BigDecimal ivaAmount) {
    }

    /**
     * Constrói as linhas da fatura a partir das linhas da PO — espelha
     * linhasFaturaAGT() do Node, partilhada (lá) entre poService e
     * contractService. `productCode` prefere sku, depois unspscCode, depois
     * o id do produto — mesma ordem de preferência.
     */
    public List<LinhaFatura> linhasFaturaAGT(List<PurchaseOrderItem> items) {
        return items.stream().map(li -> {
            var iva = taxService.computeTax(li.getLineTotal());
            var produto = li.getProduct();
            String productCode = produto != null && produto.getSku() != null ? produto.getSku()
                    : produto != null && produto.getUnspscCode() != null ? produto.getUnspscCode()
                    : li.getProductId();
            String description = produto != null && produto.getName() != null ? produto.getName() : "Produto/serviço";
            return new LinhaFatura(productCode, description, BigDecimal.valueOf(li.getQuantity()),
                    li.getUnitPrice(), iva.net(), iva.tax());
        }).toList();
    }

    public String serieFiscalDoFornecedor(String companySerieFiscal) {
        return companySerieFiscal;
    }

    public String serieNotaCreditoDoFornecedor(String companySerieFiscal) {
        return companySerieFiscal == null ? null : companySerieFiscal + "-NC";
    }

    public String serieReciboDoFornecedor(String companySerieFiscal) {
        return companySerieFiscal == null ? null : companySerieFiscal + "-RC";
    }

    /** Formato do exemplo oficial: série + ano + sequencial com 7 dígitos, sem letra de tipo; null em vez de inventar. */
    public static String numeroDocumentoAGT(String serie, int ano, Integer numeroNaSerie) {
        if (serie == null || serie.isBlank() || numeroNaSerie == null || numeroNaSerie == 0) return null;
        return serie + "." + ano + "/" + String.format("%07d", numeroNaSerie);
    }

    String textoParaAssinar(Instant emitidaEm, String serie, long numero, BigDecimal total, String hashAnterior) {
        String data = DATA.format(emitidaEm);
        String carimbo = CARIMBO.format(emitidaEm);
        String montante = total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        return String.join(";", data, carimbo, serie + "/" + numero, montante, hashAnterior == null ? "" : hashAnterior);
    }

    String calcularHash(Instant emitidaEm, String serie, long numero, BigDecimal total, String hashAnterior) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(textoParaAssinar(emitidaEm, serie, numero, total, hashAnterior).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    public record Certificacao(String serie, Integer numeroNaSerie, String hashDocumento, String hashAnterior, Instant assinadaEm) {
        static final Certificacao VAZIA = new Certificacao(null, null, null, null, null);
    }

    /**
     * Atribui série, número e hash a um documento fiscal, DENTRO da
     * transação que o cria — chamar isto fora de um {@code @Transactional}
     * do chamador deixa o {@code FOR UPDATE} sem efeito útil.
     */
    public Certificacao atribuir(Instant emitidaEm, BigDecimal total, String codigo, Instant dataAdesao) {
        if (codigo == null) return Certificacao.VAZIA;

        if (dataAdesao != null && emitidaEm.isBefore(dataAdesao)) {
            throw new IllegalStateException(
                    "Data de emissão (" + DATA.format(emitidaEm) + ") anterior à data de adesão da empresa à "
                            + "faturação eletrónica (" + DATA.format(dataAdesao) + ").");
        }

        int ano = emitidaEm.atZone(ZoneOffset.UTC).getYear();

        jdbcTemplate.update("""
                INSERT INTO "series_faturacao" ("id", "codigo", "ano", "ultimo_numero")
                VALUES (?, ?, ?, 0)
                ON CONFLICT ("codigo", "ano") DO NOTHING
                """, UUID.randomUUID().toString(), codigo, ano);

        Map<String, Object> linha = jdbcTemplate.queryForMap("""
                SELECT "ultimo_numero", "ultimo_hash", "ativa"
                  FROM "series_faturacao"
                 WHERE "codigo" = ? AND "ano" = ?
                 FOR UPDATE
                """, codigo, ano);

        if (!Boolean.TRUE.equals(linha.get("ativa"))) {
            throw new IllegalStateException("A série de faturação " + codigo + "/" + ano + " está fechada. Não se emitem documentos numa série fechada.");
        }

        long numero = ((Number) linha.get("ultimo_numero")).longValue() + 1;
        String hashAnterior = (String) linha.get("ultimo_hash");
        String hashDocumento = calcularHash(emitidaEm, codigo, numero, total, hashAnterior);

        jdbcTemplate.update("""
                UPDATE "series_faturacao"
                   SET "ultimo_numero" = ?, "ultimo_hash" = ?, "updated_at" = NOW()
                 WHERE "codigo" = ? AND "ano" = ?
                """, numero, hashDocumento, codigo, ano);

        return new Certificacao(codigo, (int) numero, hashDocumento, hashAnterior, emitidaEm);
    }
}
