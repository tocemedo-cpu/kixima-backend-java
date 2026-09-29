package ao.kixima.erp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Espelha ERP_FIELDS/ERP_SYSTEMS (erpConfigService.js) — os ERPs suportados e os campos do formulário dinâmico. */
public final class ErpFields {

    public static final Map<CompanyErpSystem, List<ErpField>> ERP_FIELDS;

    static {
        Map<CompanyErpSystem, List<ErpField>> m = new LinkedHashMap<>();
        m.put(CompanyErpSystem.MANUAL, List.of());
        m.put(CompanyErpSystem.PRIMAVERA, List.of(
                new ErpField("baseUrl", "URL base (REST)", false, true),
                new ErpField("apiKey", "API Key", true, true),
                new ErpField("company", "Empresa (código)", false, true)));
        m.put(CompanyErpSystem.SAP_S4HANA, List.of(
                new ErpField("baseUrl", "URL base (OData)", false, true),
                new ErpField("username", "Utilizador", false, true),
                new ErpField("password", "Palavra-passe", true, true),
                new ErpField("client", "Client (mandante)", false, false)));
        m.put(CompanyErpSystem.ORACLE_ERP_CLOUD, List.of(
                new ErpField("baseUrl", "URL base (Financials REST)", false, true),
                new ErpField("username", "Utilizador", false, true),
                new ErpField("password", "Palavra-passe", true, true)));
        m.put(CompanyErpSystem.SAP_ARIBA, List.of(
                new ErpField("baseUrl", "URL base (cXML)", false, true),
                new ErpField("sharedSecret", "Shared Secret", true, true),
                new ErpField("networkId", "Network ID (ANID)", false, true)));
        ERP_FIELDS = Map.copyOf(m);
    }

    public static final List<CompanyErpSystem> ERP_SYSTEMS = List.copyOf(ERP_FIELDS.keySet());

    public static boolean isRealErp(CompanyErpSystem erp) {
        return erp != null && erp != CompanyErpSystem.MANUAL;
    }

    private ErpFields() {
    }
}
