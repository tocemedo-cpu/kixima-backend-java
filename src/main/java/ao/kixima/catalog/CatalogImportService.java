package ao.kixima.catalog;

import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.plan.PlanFeatureFlag;
import ao.kixima.plan.PlanService;
import ao.kixima.storage.StorageService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Espelha backend/src/services/catalogImportService.js — importação do
 * catálogo em massa a partir de um Excel (.xlsx): categoria, tipo, UNSPSC,
 * país de origem, preço, stock, cidade, fotos embebidas (associadas por
 * posição). Idempotente: reimportar atualiza pelo slug, não duplica.
 *
 * O Node lê a folha num worker isolado (xlsxSeguro.js) com limite de tempo
 * e de memória; aqui a leitura é feita pelo Apache POI no próprio processo
 * — o limite de 25MB do upload (UploadFilters) é o tecto.
 */
@Service
public class CatalogImportService {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportService.class);
    private static final List<String> FOLHAS = List.of("catálogo", "catalogo", "catalog", "produtos", "itens");
    static final String ILEGIVEL = "Não foi possível ler o ficheiro Excel. Verifique se é um .xlsx válido.";

    private static final Map<String, List<String>> COLS = new LinkedHashMap<>();

    static {
        COLS.put("categoria", List.of("categoria", "setor", "família comercial"));
        COLS.put("nome", List.of("produto/serviço", "produto/servico", "produto", "serviço", "servico", "nome", "item", "designação"));
        COLS.put("descricao", List.of("descrição", "descricao", "descrição do item"));
        COLS.put("tipo", List.of("tipo"));
        COLS.put("uom", List.of("uom", "unidade", "un", "unidade de medida"));
        COLS.put("code", List.of("código unspsc", "codigo unspsc", "unspsc", "código", "codigo"));
        COLS.put("tituloEN", List.of("título oficial unspsc", "titulo oficial unspsc", "título unspsc", "titulo unspsc"));
        COLS.put("segmento", List.of("segmento unspsc", "segmento"));
        COLS.put("familia", List.of("família unspsc", "familia unspsc", "família", "familia"));
        COLS.put("origem", List.of("país de origem", "pais de origem", "origem", "proveniência", "proveniencia", "país", "pais", "country of origin"));
        COLS.put("preco", List.of("preço", "preco", "preço unitário", "preco unitario", "preço (aoa)", "preço unitário (aoa)", "preco (aoa)"));
        COLS.put("stock", List.of("stock", "estoque", "quantidade", "quantidade em stock", "quantidade em estoque"));
        COLS.put("cidade", List.of("cidade", "localização", "localizacao", "localidade"));
    }

    private static final Map<String, Long> BASE_PRICE = Map.ofEntries(
            Map.entry("válvulas e conexões", 850000L), Map.entry("tubulares e acessórios (octg)", 1600000L),
            Map.entry("hidráulica e pneumática", 320000L), Map.entry("bombas e compressores", 4200000L),
            Map.entry("instrumentação e controlo", 680000L), Map.entry("perfuração e completação", 5400000L),
            Map.entry("segurança e epi", 45000L), Map.entry("elétrico, iluminação e automação", 210000L),
            Map.entry("geração de energia", 7800000L), Map.entry("produtos químicos e fluidos", 180000L),
            Map.entry("elevação, içamento e rigging", 540000L), Map.entry("ferramentas e equipamento de oficina", 95000L),
            Map.entry("material de escritório e ti", 60000L), Map.entry("serviços de engenharia e manutenção", 3200000L),
            Map.entry("logística, transporte e armazenagem", 480000L), Map.entry("inspeção, testes e certificação", 950000L),
            Map.entry("serviços ambientais e gestão de resíduos", 720000L));

    private final CompanyRepository companyRepository;
    private final ProductRepository productRepository;
    private final PlanService planService;
    private final StorageService storageService;

    public CatalogImportService(CompanyRepository companyRepository, ProductRepository productRepository,
                                PlanService planService, StorageService storageService) {
        this.companyRepository = companyRepository;
        this.productRepository = productRepository;
        this.planService = planService;
        this.storageService = storageService;
    }

    // --- Auxiliares puros (portados 1:1) ------------------------------------------

    /** `String(v)` do JS para um valor de célula: números inteiros sem ".0", decimais sem notação científica. */
    static String texto(Object v) {
        if (v == null) return null;
        if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) return String.valueOf(d);
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf(d.longValue());
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }

    static String norm(Object s) {
        return s == null ? "" : texto(s).trim().toLowerCase();
    }

    static String slugify(String s) {
        String out = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase().replaceAll("[\"'()]", "").replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return out.length() > 70 ? out.substring(0, 70) : out;
    }

    static long defaultPrice(String categoria, String code) {
        long base = BASE_PRICE.getOrDefault(norm(categoria), 250000L);
        String digitos = (code == null ? "" : code).replaceAll("\\D", "");
        if (digitos.length() > 3) digitos = digitos.substring(digitos.length() - 3);
        int digits = digitos.isEmpty() ? 0 : Integer.parseInt(digitos);
        double factor = 0.85 + (digits % 30) / 100.0;
        return Math.round((base * factor) / 1000) * 1000;
    }

    /** Interpreta "1.250.000,00 AOA", "850000" ou um número; null quando vazio/ilegível. */
    public static Double parsePrice(Object v) {
        if (v == null || (v instanceof String s0 && s0.isEmpty())) return null;
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).replaceAll("[^\\d.,]", "");
        if (s.isEmpty()) return null;
        String limpo = s.replaceAll("\\.(?=\\d{3}(\\D|$))", "").replace(',', '.');
        try {
            double n = Double.parseDouble(limpo);
            return Double.isFinite(n) ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    record ImagemEmbebida(long row, byte[] buffer, String ext) {
    }

    /** Lê o .xlsx como zip: os desenhos (xl/drawings) apontam, por relação, para os ficheiros de xl/media, ancorados a uma linha. */
    static List<ImagemEmbebida> extractImages(byte[] buffer) {
        List<ImagemEmbebida> out = new ArrayList<>();
        Map<String, byte[]> entradas = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(buffer))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.isDirectory()) entradas.put(e.getName(), zip.readAllBytes());
            }
        } catch (Exception e) {
            return out;
        }
        Pattern drawing = Pattern.compile("^xl/drawings/drawing\\d+\\.xml$");
        for (String nome : entradas.keySet()) {
            if (!drawing.matcher(nome).matches()) continue;
            String xml = new String(entradas.get(nome), StandardCharsets.UTF_8);
            String relName = nome.replaceAll("drawings/(drawing\\d+)\\.xml", "drawings/_rels/$1.xml.rels");
            Map<String, String> relMap = new LinkedHashMap<>();
            if (entradas.containsKey(relName)) {
                String relXml = new String(entradas.get(relName), StandardCharsets.UTF_8);
                Matcher rel = Pattern.compile("<Relationship\\b[^>]*/>").matcher(relXml);
                while (rel.find()) {
                    Matcher id = Pattern.compile("Id=\"([^\"]+)\"").matcher(rel.group());
                    Matcher tgt = Pattern.compile("Target=\"([^\"]+)\"").matcher(rel.group());
                    if (id.find() && tgt.find()) relMap.put(id.group(1), tgt.group(1));
                }
            }
            Matcher anchors = Pattern.compile("<xdr:(one|two)CellAnchor[\\s\\S]*?</xdr:(one|two)CellAnchor>").matcher(xml);
            while (anchors.find()) {
                String a = anchors.group();
                Matcher rowM = Pattern.compile("<xdr:from>[\\s\\S]*?<xdr:row>(\\d+)</xdr:row>").matcher(a);
                long row = rowM.find() ? Long.parseLong(rowM.group(1)) : 1_000_000_000L;
                Matcher embed = Pattern.compile("r:embed=\"([^\"]+)\"").matcher(a);
                String tgt = embed.find() ? relMap.get(embed.group(1)) : null;
                if (tgt == null) continue;
                String mediaPath = tgt.replaceAll("^/", "");
                if (!mediaPath.startsWith("xl/")) mediaPath = "xl/" + mediaPath.replaceAll("^\\.\\./", "");
                byte[] dados = entradas.get(mediaPath);
                if (dados == null) continue;
                String ext = mediaPath.contains(".") ? mediaPath.substring(mediaPath.lastIndexOf('.') + 1).toLowerCase() : "jpg";
                out.add(new ImagemEmbebida(row, dados, ext));
            }
        }
        out.sort(Comparator.comparingLong(ImagemEmbebida::row));
        return out;
    }

    private static Object valorDaCelula(Cell c) {
        if (c == null) return null;
        CellType t = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        return switch (t) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> c.getNumericCellValue();
            case BOOLEAN -> c.getBooleanCellValue();
            default -> null;
        };
    }

    /** Espelha xlsxSeguro.lerLinhas + workers/lerXlsx.js: a folha pelo nome (ou a primeira), linhas como arrays com null nos vazios. */
    static List<List<Object>> lerLinhas(byte[] buffer, List<String> nomesDeFolha) {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(buffer))) {
            Sheet ws = null;
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (nomesDeFolha.contains(wb.getSheetName(i).trim().toLowerCase())) {
                    ws = wb.getSheetAt(i);
                    break;
                }
            }
            if (ws == null && wb.getNumberOfSheets() > 0) ws = wb.getSheetAt(0);
            if (ws == null) return List.of();
            int largura = 0;
            for (Row r : ws) largura = Math.max(largura, r.getLastCellNum());
            List<List<Object>> linhas = new ArrayList<>();
            int ultima = ws.getLastRowNum();
            for (int i = 0; i <= ultima; i++) {
                Row r = ws.getRow(i);
                List<Object> linha = new ArrayList<>(largura);
                for (int j = 0; j < largura; j++) linha.add(r == null ? null : valorDaCelula(r.getCell(j)));
                linhas.add(linha);
            }
            return linhas;
        } catch (Exception e) {
            log.error("Leitura de Excel falhou: {}", e.getMessage());
            throw new BusinessRuleException(ILEGIVEL);
        }
    }

    private static Object cel(List<Object> r, int idx) {
        return idx < 0 || idx >= r.size() ? null : r.get(idx);
    }

    private static boolean vazio(String s) {
        return s == null || s.isEmpty();
    }

    private static String primeirosDigitos(Object v) {
        Matcher m = Pattern.compile("\\d+").matcher(v == null ? "" : texto(v));
        return m.find() ? m.group() : "";
    }

    /** Uma linha já interpretada, à espera de gravar. */
    private static final class Linha {
        int i;
        int excelRow;
        String nome;
        String code;
        String slug;
        Map<String, Object> data;
        ImagemEmbebida im;
        boolean comImagem;
        String erro;
    }

    private static Map<String, Object> erro(int row, String mensagem) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("row", row);
        m.put("error", mensagem);
        return m;
    }

    /**
     * @return { total, created, updated, withImages, errors: [{row, error}], warnings, precosEstimados, stockPorOmissao, localizacaoPorOmissao }
     */
    @Transactional
    public Map<String, Object> importCatalog(byte[] buffer, String supplierId) {
        Company empresa = companyRepository.findById(supplierId).orElseThrow(() -> new NotFoundException("Empresa"));
        planService.assertFeature(empresa, PlanFeatureFlag.CARREGAMENTO_EM_MASSA, "Carregamento em massa");

        List<List<Object>> rows = lerLinhas(buffer, FOLHAS);
        if (rows.isEmpty()) throw new BusinessRuleException("A folha do catálogo está vazia.");

        List<String> hdr = rows.get(0).stream().map(CatalogImportService::norm).toList();
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : COLS.entrySet()) {
            int pos = -1;
            for (int i = 0; i < hdr.size(); i++) if (e.getValue().contains(hdr.get(i))) {
                pos = i;
                break;
            }
            idx.put(e.getKey(), pos);
        }
        if (idx.get("nome") < 0 || idx.get("categoria") < 0) {
            throw new BusinessRuleException("Formato inválido: são necessárias, no mínimo, as colunas \"Categoria\" e \"Produto/Serviço\".");
        }

        List<ImagemEmbebida> images = extractImages(buffer);
        List<List<Object>> dataRows = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<Object> r = rows.get(i);
            Object nome = cel(r, idx.get("nome"));
            if (nome != null && !texto(nome).trim().isEmpty()) dataRows.add(r);
        }

        List<String> warnings = new ArrayList<>();
        if (!images.isEmpty() && images.size() != dataRows.size()) {
            warnings.add("O ficheiro tem " + images.size() + " imagem(ns) mas " + dataRows.size() + " linha(s) de dados — "
                    + "as fotos são associadas por posição (ordem na folha), por isso a correspondência pode estar incorreta. "
                    + "Reveja as fotos publicadas no catálogo.");
        }

        int precosEstimados = 0;
        int stockPorOmissao = 0;
        int localizacaoPorOmissao = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Linha> linhas = new ArrayList<>();

        for (int i = 0; i < dataRows.size(); i++) {
            List<Object> r = dataRows.get(i);
            int excelRow = i + 2; // linha real no Excel (cabeçalho = 1)
            try {
                String nome = texto(cel(r, idx.get("nome"))).trim();
                Object catBruta = cel(r, idx.get("categoria"));
                String categoria = catBruta == null || texto(catBruta).isEmpty() ? "Geral" : texto(catBruta).trim();
                boolean isService = norm(idx.get("tipo") >= 0 ? cel(r, idx.get("tipo")) : "").startsWith("serv");
                Object codeBruto = idx.get("code") >= 0 ? cel(r, idx.get("code")) : null;
                String code = codeBruto == null ? null : texto(codeBruto).trim();
                String seg2 = idx.get("segmento") >= 0 ? primeirosDigitos(cel(r, idx.get("segmento"))) : "";
                String fam4 = idx.get("familia") >= 0 ? primeirosDigitos(cel(r, idx.get("familia"))) : "";
                Object origemBruta = idx.get("origem") >= 0 ? cel(r, idx.get("origem")) : null;
                String origem = origemBruta == null || texto(origemBruta).trim().isEmpty() ? null : texto(origemBruta).trim();
                Double precoDaFolha = parsePrice(idx.get("preco") >= 0 ? cel(r, idx.get("preco")) : null);
                double price = precoDaFolha != null ? precoDaFolha : defaultPrice(categoria, code);
                if (precoDaFolha == null) precosEstimados++;

                Double stockDaFolha = idx.get("stock") >= 0 ? parsePrice(cel(r, idx.get("stock"))) : null;
                Integer stockQuantity;
                if (isService) {
                    stockQuantity = null;
                } else if (stockDaFolha != null) {
                    stockQuantity = (int) Math.max(0, Math.round(stockDaFolha));
                } else {
                    stockQuantity = 50;
                    stockPorOmissao++;
                }

                Object cidadeBruta = idx.get("cidade") >= 0 ? cel(r, idx.get("cidade")) : null;
                String cidadeDaFolha = cidadeBruta == null ? "" : texto(cidadeBruta).trim();
                String cidade = cidadeDaFolha.isEmpty() ? "Luanda" : cidadeDaFolha;
                if (cidadeDaFolha.isEmpty()) localizacaoPorOmissao++;

                String slug = slugify(nome) + "-" + (vazio(code) ? String.valueOf(i) : code) + "-" + supplierId.substring(0, Math.min(8, supplierId.length()));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("name", nome);
                data.put("sku", code);
                data.put("category", categoria);
                data.put("description", idx.get("descricao") >= 0 ? texto(cel(r, idx.get("descricao"))) : null);
                data.put("kind", isService ? ProductKind.SERVICO : ProductKind.PRODUTO);
                Object uom = idx.get("uom") >= 0 ? cel(r, idx.get("uom")) : null;
                data.put("measurementUnit", uom == null || texto(uom).isEmpty() ? "un" : texto(uom));
                data.put("unspscCode", code);
                data.put("unspscTitle", idx.get("tituloEN") >= 0 ? texto(cel(r, idx.get("tituloEN"))) : null);
                data.put("unspscSegment", seg2.isEmpty() ? null : seg2);
                data.put("unspscFamily", fam4.isEmpty() ? null : fam4);
                data.put("unspscClass", vazio(code) ? null : code.substring(0, Math.min(6, code.length())));
                data.put("countryOfOrigin", origem);
                data.put("unitPrice", BigDecimal.valueOf(price));
                data.put("currency", "AOA");
                data.put("leadTimeDays", isService ? 5 : 15);
                data.put("availability", "Em stock");
                data.put("stockQuantity", stockQuantity);
                data.put("city", cidade);
                data.put("province", cidade);
                data.put("country", "Angola");
                data.put("active", true);

                Linha l = new Linha();
                l.i = i;
                l.excelRow = excelRow;
                l.nome = nome;
                l.code = code;
                l.slug = slug;
                l.data = data;
                l.im = i < images.size() ? images.get(i) : null;
                linhas.add(l);
            } catch (Exception e) {
                errors.add(erro(excelRow, e.getMessage()));
            }
        }

        // Fotos embebidas — guardadas antes das linhas (bytes primeiro, linhas depois).
        for (Linha linha : linhas) {
            if (linha.im == null) continue;
            try {
                String ext = "jpeg".equals(linha.im.ext()) ? "jpg" : linha.im.ext();
                String mimetype = "image/" + ("jpg".equals(linha.im.ext()) ? "jpeg" : linha.im.ext());
                String url = storageService.saveFile(linha.im.buffer(),
                        (vazio(linha.code) ? slugify(linha.nome) : linha.code) + "." + ext, mimetype,
                        "cat-" + supplierId.substring(0, Math.min(8, supplierId.length())) + "-" + (vazio(linha.code) ? String.valueOf(linha.i) : linha.code), "catalog");
                linha.data.put("imageUrl", url);
                linha.comImagem = true;
            } catch (Exception e) {
                linha.erro = e.getMessage();
            }
        }
        for (Linha l : linhas) if (l.erro != null) errors.add(erro(l.excelRow, l.erro));
        List<Linha> linhasValidas = linhas.stream().filter(l -> l.erro == null).toList();

        Map<String, Product> existentes = new LinkedHashMap<>();
        if (!linhasValidas.isEmpty()) {
            for (Product p : productRepository.findBySlugIn(linhasValidas.stream().map(l -> l.slug).toList())) existentes.put(p.getSlug(), p);
        }

        int created = 0;
        int updated = 0;
        int withImages = 0;
        // No Node as linhas gravam em paralelo e a segunda ocorrência do mesmo código no ficheiro rebenta na
        // restrição única do slug. Aqui é detetado antes de tocar na base — mesmo resultado, sem depender da ordem.
        Set<String> vistosNoFicheiro = new HashSet<>();
        Instant agora = Instant.now();
        for (Linha linha : linhasValidas) {
            try {
                if (!vistosNoFicheiro.add(linha.slug)) {
                    throw new BusinessRuleException("Linha repetida no ficheiro (mesmo produto e código) — a primeira ocorrência já foi gravada.");
                }
                Product existente = existentes.get(linha.slug);
                if (existente != null) {
                    aplicar(existente, linha.data);
                    existente.touch();
                    updated++;
                } else {
                    Product novo = new Product(UUID.randomUUID().toString(), supplierId, linha.nome, (String) linha.data.get("category"),
                            (BigDecimal) linha.data.get("unitPrice"), linha.slug, agora);
                    aplicar(novo, linha.data);
                    productRepository.save(novo);
                    created++;
                }
                if (linha.comImagem) withImages++;
            } catch (Exception e) {
                errors.add(erro(linha.excelRow, e.getMessage()));
            }
        }
        errors.sort(Comparator.comparingInt(e -> (Integer) e.get("row")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", dataRows.size());
        out.put("created", created);
        out.put("updated", updated);
        out.put("withImages", withImages);
        out.put("errors", errors);
        out.put("warnings", warnings);
        out.put("precosEstimados", precosEstimados);
        out.put("stockPorOmissao", stockPorOmissao);
        out.put("localizacaoPorOmissao", localizacaoPorOmissao);
        return out;
    }

    /** O `data` do `prisma.product.create/update` — todos os campos que a importação define. */
    private static void aplicar(Product p, Map<String, Object> data) {
        p.setName((String) data.get("name"));
        p.setSku((String) data.get("sku"));
        p.setCategory((String) data.get("category"));
        p.setDescription((String) data.get("description"));
        p.setKind((ProductKind) data.get("kind"));
        p.setMeasurementUnit((String) data.get("measurementUnit"));
        p.setUnspscCode((String) data.get("unspscCode"));
        p.setUnspscTitle((String) data.get("unspscTitle"));
        p.setUnspscSegment((String) data.get("unspscSegment"));
        p.setUnspscFamily((String) data.get("unspscFamily"));
        p.setUnspscClass((String) data.get("unspscClass"));
        p.setCountryOfOrigin((String) data.get("countryOfOrigin"));
        p.setUnitPrice((BigDecimal) data.get("unitPrice"));
        p.setCurrency((String) data.get("currency"));
        p.setLeadTimeDays((Integer) data.get("leadTimeDays"));
        p.setAvailability((String) data.get("availability"));
        p.setStockQuantity((Integer) data.get("stockQuantity"));
        p.setCity((String) data.get("city"));
        p.setProvince((String) data.get("province"));
        p.setCountry((String) data.get("country"));
        p.setActive(Boolean.TRUE.equals(data.get("active")));
        if (data.containsKey("imageUrl")) p.setImageUrl((String) data.get("imageUrl"));
    }
}
