package ao.kixima.catalog.dto;

import ao.kixima.catalog.Product;
import ao.kixima.common.error.ValidationException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Espelha createProductSchema/updateProductSchema (backend/src/utils/schemas.js)
 * — a ficha do produto tal como o zod a deixa: `''`/null nos campos
 * opcionais desaparecem, números vêm coagidos de texto (multipart) ou de
 * JSON, chaves desconhecidas são descartadas. {@link #camposAlterados()} é
 * o `Object.keys(req.body)` DEPOIS da validação, tal como o Node regista.
 */
public final class ProductPayload {

    /** optText — texto livre opcional. */
    static final List<String> TEXTO = List.of(
            "sku", "manufacturerCode", "subcategory", "brand", "manufacturer", "model", "countryOfOrigin",
            "description", "fullDescription", "applications", "benefits", "keywords",
            "unspscCode", "unspscTitle", "unspscSegment", "unspscFamily", "unspscClass",
            "keySpec", "standard", "warranty", "incoterm", "supplierNotes", "imageUrl",
            "material", "weight", "height", "width", "length", "pressure", "temperature", "power", "voltage",
            "measurementUnit", "warehouse", "availability");
    /** optInt — inteiro não negativo opcional. */
    static final List<String> INTEIROS = List.of("minQuantity", "maxQuantity", "stockQuantity", "leadTimeDays", "minStock");
    private static final Set<String> OBRIGATORIOS_TEXTO = Set.of("name", "category");

    private final Map<String, Object> valores;

    private ProductPayload(Map<String, Object> valores) {
        this.valores = valores;
    }

    private static boolean ausente(Object v) {
        return v == null || (v instanceof String s && s.isEmpty());
    }

    private static Double numero(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @param raw     corpo do pedido (parâmetros multipart ou JSON)
     * @param criacao createProductSchema (nome, categoria e preço obrigatórios) vs. `.partial()`
     */
    public static ProductPayload parse(Map<String, ?> raw, boolean criacao) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, List<String>> erros = new LinkedHashMap<>();
        Map<String, ?> corpo = raw == null ? Map.of() : raw;

        for (String campo : OBRIGATORIOS_TEXTO) {
            boolean presente = corpo.containsKey(campo);
            Object v = corpo.get(campo);
            if (!presente || v == null) {
                if (criacao || presente) erros.computeIfAbsent(campo, k -> new ArrayList<>()).add("Required");
                continue;
            }
            if (!(v instanceof String s)) {
                erros.computeIfAbsent(campo, k -> new ArrayList<>()).add("Expected string");
            } else if (s.length() < 2) {
                erros.computeIfAbsent(campo, k -> new ArrayList<>()).add("String must contain at least 2 character(s)");
            } else {
                out.put(campo, s);
            }
        }

        for (String campo : TEXTO) {
            if (!corpo.containsKey(campo)) continue;
            Object v = corpo.get(campo);
            if (ausente(v)) continue;
            if (v instanceof String s) out.put(campo, s);
            else erros.computeIfAbsent(campo, k -> new ArrayList<>()).add("Expected string");
        }

        // unitPrice: z.coerce.number().positive() — obrigatório na criação.
        if (corpo.containsKey("unitPrice") || criacao) {
            Object v = corpo.get("unitPrice");
            Double n = v == null ? null : numero(v);
            if (n == null || n.isNaN() || n.isInfinite()) {
                erros.computeIfAbsent("unitPrice", k -> new ArrayList<>()).add(v == null ? "Required" : "Expected number");
            } else if (n <= 0) {
                erros.computeIfAbsent("unitPrice", k -> new ArrayList<>()).add("Number must be greater than 0");
            } else {
                out.put("unitPrice", BigDecimal.valueOf(n));
            }
        }
        // promoPrice: optNum — não negativo.
        if (corpo.containsKey("promoPrice") && !ausente(corpo.get("promoPrice"))) {
            Double n = numero(corpo.get("promoPrice"));
            if (n == null || n.isNaN() || n < 0) erros.computeIfAbsent("promoPrice", k -> new ArrayList<>()).add("Expected non-negative number");
            else out.put("promoPrice", BigDecimal.valueOf(n));
        }
        // currency: z.string().default('AOA') — o default só entra na criação (partial() torna-a opcional).
        if (corpo.containsKey("currency") && corpo.get("currency") != null) {
            if (corpo.get("currency") instanceof String s) out.put("currency", s);
            else erros.computeIfAbsent("currency", k -> new ArrayList<>()).add("Expected string");
        } else if (criacao) {
            out.put("currency", "AOA");
        }
        for (String campo : INTEIROS) {
            if (!corpo.containsKey(campo) || ausente(corpo.get(campo))) continue;
            Double n = numero(corpo.get(campo));
            if (n == null || n.isNaN() || n < 0 || n != Math.rint(n)) {
                erros.computeIfAbsent(campo, k -> new ArrayList<>()).add("Expected non-negative integer");
            } else {
                out.put(campo, n.intValue());
            }
        }

        if (!erros.isEmpty()) {
            Map<String, Object> flatten = new LinkedHashMap<>();
            flatten.put("formErrors", List.of());
            flatten.put("fieldErrors", erros);
            throw new ValidationException("Dados inválidos.", flatten);
        }
        return new ProductPayload(out);
    }

    public List<String> camposAlterados() {
        return new ArrayList<>(valores.keySet());
    }

    public String texto(String campo) {
        Object v = valores.get(campo);
        return v == null ? null : String.valueOf(v);
    }

    public BigDecimal unitPrice() {
        return (BigDecimal) valores.get("unitPrice");
    }

    public boolean tem(String campo) {
        return valores.containsKey(campo);
    }

    /** Aplica ao produto SÓ os campos presentes — o `data` do `prisma.product.update`. */
    public void aplicar(Product p) {
        for (Map.Entry<String, Object> e : valores.entrySet()) {
            Object v = e.getValue();
            switch (e.getKey()) {
                case "name" -> p.setName((String) v);
                case "category" -> p.setCategory((String) v);
                case "sku" -> p.setSku((String) v);
                case "manufacturerCode" -> p.setManufacturerCode((String) v);
                case "subcategory" -> p.setSubcategory((String) v);
                case "brand" -> p.setBrand((String) v);
                case "manufacturer" -> p.setManufacturer((String) v);
                case "model" -> p.setModel((String) v);
                case "countryOfOrigin" -> p.setCountryOfOrigin((String) v);
                case "description" -> p.setDescription((String) v);
                case "fullDescription" -> p.setFullDescription((String) v);
                case "applications" -> p.setApplications((String) v);
                case "benefits" -> p.setBenefits((String) v);
                case "keywords" -> p.setKeywords((String) v);
                case "unspscCode" -> p.setUnspscCode((String) v);
                case "unspscTitle" -> p.setUnspscTitle((String) v);
                case "unspscSegment" -> p.setUnspscSegment((String) v);
                case "unspscFamily" -> p.setUnspscFamily((String) v);
                case "unspscClass" -> p.setUnspscClass((String) v);
                case "keySpec" -> p.setKeySpec((String) v);
                case "standard" -> p.setStandard((String) v);
                case "warranty" -> p.setWarranty((String) v);
                case "incoterm" -> p.setIncoterm((String) v);
                case "supplierNotes" -> p.setSupplierNotes((String) v);
                case "imageUrl" -> p.setImageUrl((String) v);
                case "material" -> p.setMaterial((String) v);
                case "weight" -> p.setWeight((String) v);
                case "height" -> p.setHeight((String) v);
                case "width" -> p.setWidth((String) v);
                case "length" -> p.setLength((String) v);
                case "pressure" -> p.setPressure((String) v);
                case "temperature" -> p.setTemperature((String) v);
                case "power" -> p.setPower((String) v);
                case "voltage" -> p.setVoltage((String) v);
                case "measurementUnit" -> p.setMeasurementUnit((String) v);
                case "warehouse" -> p.setWarehouse((String) v);
                case "availability" -> p.setAvailability((String) v);
                case "unitPrice" -> p.setUnitPrice((BigDecimal) v);
                case "promoPrice" -> p.setPromoPrice((BigDecimal) v);
                case "currency" -> p.setCurrency((String) v);
                case "minQuantity" -> p.setMinQuantity((Integer) v);
                case "maxQuantity" -> p.setMaxQuantity((Integer) v);
                case "stockQuantity" -> p.setStockQuantity((Integer) v);
                case "leadTimeDays" -> p.setLeadTimeDays((Integer) v);
                case "minStock" -> p.setMinStock((Integer) v);
                default -> throw new IllegalStateException("Campo não mapeado: " + e.getKey());
            }
        }
        p.touch();
    }
}
