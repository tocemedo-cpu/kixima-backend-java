package ao.kixima.apicatalogo;

import ao.kixima.apikey.ApiKeyService;
import ao.kixima.audit.Actor;
import ao.kixima.audit.AuditService;
import ao.kixima.catalog.Product;
import ao.kixima.catalog.ProductRepository;
import ao.kixima.common.error.ErrorResponse;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Espelha backend/src/routes/apiCatalogoRoutes.js — a API de catálogo do
 * plano Pro, autenticada por CHAVE (nunca por sessão: um sistema não
 * introduz um código de 6 dígitos). O alcance é o catálogo da própria
 * empresa e nada mais; 120 pedidos por minuto por chave (Bucket4j, o
 * equivalente ao express-rate-limit); o que a máquina altera deixa o mesmo
 * rasto que a pessoa altera.
 *
 * Fora da sessão JWT de propósito (PublicPaths): uma chave apresentada
 * como Bearer não é um JWT, e a rota decide sozinha quem entra.
 */
@RestController
@RequestMapping("/api/v1/catalogo")
public class ApiCatalogoController {

    private final ApiKeyService apiKeyService;
    private final ProductRepository productRepository;
    private final AuditService auditService;
    /** 120 por minuto por chave (express-rate-limit: janela fixa de 60s) — configurável só para os testes. */
    private final int limitePorMinuto;
    private final Map<String, Bucket> baldes = new ConcurrentHashMap<>();

    public ApiCatalogoController(ApiKeyService apiKeyService, ProductRepository productRepository, AuditService auditService,
                                 @Value("${kixima.api-catalogo.limite-por-minuto:120}") int limitePorMinuto) {
        this.apiKeyService = apiKeyService;
        this.productRepository = productRepository;
        this.auditService = auditService;
        this.limitePorMinuto = limitePorMinuto;
    }

    // --- Autenticação por chave + limite de taxa -----------------------------------

    private static final class Recusa extends RuntimeException {
        final ResponseEntity<?> resposta;

        Recusa(ResponseEntity<?> resposta) {
            this.resposta = resposta;
        }
    }

    private static ResponseEntity<?> erro(int status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }

    private Bucket balde(String chave) {
        return baldes.computeIfAbsent(chave, k -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(limitePorMinuto).refillIntervally(limitePorMinuto, Duration.ofMinutes(1)).build())
                .build());
    }

    private ApiKeyService.Sessao autenticar(HttpServletRequest req) {
        String header = req.getHeader("authorization");
        String apresentada = header != null && header.startsWith("Bearer ") ? header.substring(7).trim()
                : (req.getHeader("x-api-key") == null ? "" : req.getHeader("x-api-key").trim());
        if (apresentada.isEmpty()) {
            throw new Recusa(erro(401, "SEM_CHAVE", "Envie a chave em \"Authorization: Bearer kxm_....\". "
                    + "As chaves criam-se em Catálogo → API, no plano Pro."));
        }
        ApiKeyService.Sessao sessao = apiKeyService.autenticar(apresentada);
        if (sessao == null) throw new Recusa(erro(401, "CHAVE_INVALIDA", "Chave inválida, revogada ou sem acesso à API."));

        ConsumptionProbe probe = balde(sessao.prefixo()).tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long segundos = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            throw new Recusa(ResponseEntity.status(429)
                    .header("RateLimit-Limit", String.valueOf(limitePorMinuto))
                    .header("RateLimit-Remaining", "0")
                    .header("RateLimit-Reset", String.valueOf(segundos))
                    .header("Retry-After", String.valueOf(segundos))
                    .body(ErrorResponse.of("RATE_LIMIT", "Demasiados pedidos. O limite é de " + limitePorMinuto + " por minuto por chave.")));
        }
        return sessao;
    }

    private ResponseEntity<?> comChave(HttpServletRequest req, java.util.function.Function<ApiKeyService.Sessao, ResponseEntity<?>> corpo) {
        try {
            ApiKeyService.Sessao sessao = autenticar(req);
            long restantes = balde(sessao.prefixo()).getAvailableTokens();
            ResponseEntity<?> r = corpo.apply(sessao);
            return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders())
                    .header("RateLimit-Limit", String.valueOf(limitePorMinuto))
                    .header("RateLimit-Remaining", String.valueOf(restantes))
                    .body(r.getBody());
        } catch (Recusa recusa) {
            return recusa.resposta;
        }
    }

    // --- A forma de cada item ----------------------------------------------------------

    static Map<String, Object> item(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku", p.getSku());
        m.put("nome", p.getName());
        m.put("descricao", p.getDescription());
        m.put("categoria", p.getCategory());
        m.put("unspsc", p.getUnspscCode());
        m.put("preco", p.getUnitPrice() == null ? 0d : p.getUnitPrice().doubleValue());
        m.put("precoPromocional", p.getPromoPrice() == null ? null : p.getPromoPrice().doubleValue());
        m.put("moeda", p.getCurrency());
        m.put("stock", p.getStockQuantity());
        m.put("disponibilidade", p.getAvailability());
        m.put("prazoEntregaDias", p.getLeadTimeDays());
        m.put("paisDeOrigem", p.getCountryOfOrigin());
        m.put("ativo", p.isActive());
        m.put("atualizadoEm", p.getUpdatedAt());
        return m;
    }

    private static int inteiro(String v, int porOmissao) {
        if (v == null || v.isBlank()) return porOmissao;
        try {
            double n = Double.parseDouble(v.trim());
            return Double.isNaN(n) || n == 0 ? porOmissao : (int) n;
        } catch (NumberFormatException e) {
            return porOmissao;
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> listar(@RequestParam(required = false) String limite, @RequestParam(required = false) String pagina,
                                    @RequestParam(required = false) String ativo, HttpServletRequest req) {
        return comChave(req, sessao -> {
            int lim = Math.min(200, Math.max(1, inteiro(limite, 50)));
            int pag = Math.max(1, inteiro(pagina, 1));
            String supplierId = sessao.empresa().getId();
            Boolean filtroAtivo = "true".equals(ativo) ? Boolean.TRUE : "false".equals(ativo) ? Boolean.FALSE : null;
            long total = filtroAtivo == null ? productRepository.countBySupplierId(supplierId) : productRepository.countBySupplierIdAndActive(supplierId, filtroAtivo);
            PageRequest page = PageRequest.of(pag - 1, lim);
            List<Product> itens = filtroAtivo == null
                    ? productRepository.findBySupplierIdOrderByUpdatedAtDescIdAsc(supplierId, page)
                    : productRepository.findBySupplierIdAndActiveOrderByUpdatedAtDescIdAsc(supplierId, filtroAtivo, page);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", total);
            out.put("pagina", pag);
            out.put("limite", lim);
            out.put("paginas", Math.max(1, (int) Math.ceil(total / (double) lim)));
            out.put("itens", itens.stream().map(ApiCatalogoController::item).toList());
            return ResponseEntity.ok(out);
        });
    }

    private static ResponseEntity<?> naoEncontrado(String sku) {
        return erro(404, "NAO_ENCONTRADO", "Não existe nenhum item com o SKU \"" + sku + "\" nesta empresa.");
    }

    @GetMapping("/{sku}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> obter(@PathVariable String sku, HttpServletRequest req) {
        return comChave(req, sessao -> productRepository.findFirstBySupplierIdAndSku(sessao.empresa().getId(), sku)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(item(p))).orElseGet(() -> naoEncontrado(sku)));
    }

    private static Double numero(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b ? 1d : 0d; // Number(true) === 1, como no JS
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    @PatchMapping("/{sku}")
    @Transactional
    public ResponseEntity<?> atualizar(@PathVariable String sku, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        return comChave(req, sessao -> {
            Product existente = productRepository.findFirstBySupplierIdAndSku(sessao.empresa().getId(), sku).orElse(null);
            if (existente == null) return naoEncontrado(sku);
            Map<String, Object> b = body == null ? Map.of() : body;
            List<String> campos = new ArrayList<>();
            List<String> erros = new ArrayList<>();

            if (b.containsKey("preco")) {
                Double n = numero(b.get("preco"));
                if (n == null || !(n > 0)) erros.add("preco tem de ser um número maior do que zero");
                else {
                    existente.setUnitPrice(BigDecimal.valueOf(n));
                    campos.add("unitPrice");
                }
            }
            if (b.containsKey("precoPromocional")) {
                if (b.get("precoPromocional") == null) {
                    existente.setPromoPrice(null);
                    campos.add("promoPrice");
                } else {
                    Double n = numero(b.get("precoPromocional"));
                    if (!(n > 0)) erros.add("precoPromocional tem de ser um número maior do que zero, ou null");
                    else {
                        existente.setPromoPrice(BigDecimal.valueOf(n));
                        campos.add("promoPrice");
                    }
                }
            }
            if (b.containsKey("stock")) {
                Double n = numero(b.get("stock"));
                if (n == null || n.isNaN() || n != Math.rint(n) || n < 0) erros.add("stock tem de ser um inteiro igual ou maior do que zero");
                else {
                    existente.setStockQuantity(n.intValue());
                    campos.add("stockQuantity");
                }
            }
            if (b.containsKey("disponibilidade")) {
                existente.setAvailability(String.valueOf(b.get("disponibilidade")));
                campos.add("availability");
            }
            if (b.containsKey("prazoEntregaDias")) {
                Double n = numero(b.get("prazoEntregaDias"));
                // O Node passava Number(x) tal e qual à base (um NaN rebentava em 500); aqui diz-se o campo.
                if (n == null || n.isNaN() || n != Math.rint(n) || n < 0) erros.add("prazoEntregaDias tem de ser um inteiro igual ou maior do que zero");
                else {
                    existente.setLeadTimeDays(n.intValue());
                    campos.add("leadTimeDays");
                }
            }
            if (b.containsKey("ativo")) {
                Object v = b.get("ativo");
                boolean ativo = v instanceof Boolean bo ? bo : v != null && !"".equals(v) && !"false".equals(v) && !Integer.valueOf(0).equals(v);
                existente.setActive(ativo);
                campos.add("active");
            }
            if (!erros.isEmpty()) return erro(422, "DADOS_INVALIDOS", String.join("; ", erros));
            if (campos.isEmpty()) {
                return erro(422, "NADA_A_ALTERAR", "Envie pelo menos um de: preco, precoPromocional, stock, disponibilidade, prazoEntregaDias, ativo.");
            }
            existente.touch();
            productRepository.save(existente);

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("campos", campos);
            detail.put("chave", sessao.prefixo());
            auditService.recordSafe(new AuditService.Entry(
                    new Actor(null, "API (" + sessao.prefixo() + ")", null, sessao.empresa().getId(), req.getRemoteAddr()),
                    "CATALOGO_ATUALIZADO_POR_API", "Product", existente.getId(),
                    existente.getSku() == null || existente.getSku().isBlank() ? existente.getName() : existente.getSku(), detail));
            return ResponseEntity.ok(item(existente));
        });
    }
}
