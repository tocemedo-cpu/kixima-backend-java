package ao.kixima.policy.dto;

import java.util.List;

/** Espelha o corpo de `GET /api/policies/company/:companyId?` — as duas apólices lado a lado. */
public record CompanyPoliciesDto(List<SupplierPolicyDto> supplierToKixima, List<ClientPolicyDto> kiximaToClient) {
}
