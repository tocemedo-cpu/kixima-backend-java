package ao.kixima.po.dto;

/** Corpo de rejeição/recusa — `reason` opcional na rejeição do Company Admin, mas sempre presente na recusa do fornecedor. */
public record ReasonRequest(String reason) {
}
