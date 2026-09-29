package ao.kixima.supplierdev.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Espelha supplierDevSchema (utils/schemas.js). {@code employees} e
 * {@code feeAccepted} viajam como {@link JsonNode} de propósito: o zod
 * distingue "não veio" de {@code null}, e {@code z.coerce.number()} aceita
 * "24" mas recusa 1.5 ("Expected integer, received float") — coisas que um
 * {@code Integer}/{@code Boolean} do Jackson coagiria em silêncio.
 */
public record CreateSupplierDevRequest(String companyName, String taxId, String contactName, String contactEmail,
                                        String contactPhone, String province, String sector, JsonNode employees,
                                        String track, String needs, JsonNode feeAccepted) {
}
