package ao.kixima.erp;

/** Espelha uma entrada de ERP_FIELDS (erpConfigService.js) — o formulário dinâmico que o frontend usa. */
public record ErpField(String key, String label, boolean secret, boolean required) {
}
