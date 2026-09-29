package ao.kixima.agt;

import java.util.Map;

/**
 * Espelha backend/src/config/agt.js — URLs dos endpoints da Sandbox/
 * homologação (hml) e produção (prd) da AGT. Única responsabilidade deste
 * ficheiro (tal como no Node): resolver o URL por nome, conforme
 * {@code kixima.agt.env}. Credenciais e chave privada NÃO vivem aqui — vêm
 * de {@code kixima.agt.*} (application.yml), injectadas nos serviços que
 * precisam delas.
 */
final class AgtEndpoints {

    private static final Map<String, Map<String, String>> ENDPOINTS = Map.of(
            "hml", Map.of(
                    "registarFactura", "https://sifphml.minfin.gov.ao/sigt/fe/v1/registarFactura",
                    "consultarFactura", "https://sifphml.minfin.gov.ao/sigt/fe/v1/consultarFactura",
                    "obterEstado", "https://sifphml.minfin.gov.ao/sigt/fe/v1/obterEstado",
                    "solicitarSerie", "https://sifphml.minfin.gov.ao/sigt/fe/v1/solicitarSerie",
                    "listarSeries", "https://sifphml.minfin.gov.ao/sigt/fe/v1/listarSeries",
                    "listarFacturas", "https://sifphml.minfin.gov.ao/sigt/fe/v1/listarFacturas"),
            "prd", Map.of(
                    "registarFactura", "https://sifp.minfin.gov.ao/sigt/fe/v1/registarFactura",
                    "consultarFactura", "https://sifp.minfin.gov.ao/sigt/fe/v1/consultarFactura",
                    "obterEstado", "https://sifp.minfin.gov.ao/sigt/fe/v1/obterEstado",
                    "solicitarSerie", "https://sifp.minfin.gov.ao/sigt/fe/v1/solicitarSerie",
                    "listarSeries", "https://sifp.minfin.gov.ao/sigt/fe/v1/listarSeries",
                    "listarFacturas", "https://sifp.minfin.gov.ao/sigt/fe/v1/listarFacturas"));

    private AgtEndpoints() {
    }

    static String resolve(String environment, String nomeEndpoint) {
        String env = environment == null || environment.isBlank() ? "hml" : environment;
        Map<String, String> porAmbiente = ENDPOINTS.getOrDefault(env, ENDPOINTS.get("hml"));
        String url = porAmbiente.get(nomeEndpoint);
        if (url == null) {
            throw new IllegalArgumentException("Endpoint AGT desconhecido: " + nomeEndpoint);
        }
        return url;
    }
}
