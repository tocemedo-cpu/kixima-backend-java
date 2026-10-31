package ao.kixima.company.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Estado do cadastro empresarial tal como o utilizador o vê: o que já preencheu (para não lho voltar a pedir), os
 * documentos já enviados, o que falta e se já pode submeter. {@code editable} é falso enquanto a empresa está em análise
 * ou depois de aprovada.
 */
public record OnboardingDto(String status, String type, boolean editable, Instant submittedAt, Instant approvedAt, Instant rejectedAt,
                            String rejectionReason, Empresa empresa, Apolice apolice, List<Documento> documentos,
                            List<Etapa> etapas, List<String> pendencias, boolean podeSubmeter) {

    public record Empresa(String name, String taxId, String contactEmail, String contactPhone, String address, String city,
                          String province, String country, Integer employees, BigDecimal annualRevenueUsd, String logoUrl,
                          boolean termsAccepted) {
    }

    /** Apólice Fornecedor→KIXIMA (só Prestadoras). Valores em texto para voltarem ao formulário exactamente como foram escritos. */
    public record Apolice(String insurer, String policyNumber, String coverageAmount, String currency, String validFrom,
                          String validUntil, String documentUrl, String documentName) {
    }

    public record Documento(String type, String label, boolean required, boolean uploaded, String originalName, String fileUrl,
                            Instant uploadedAt) {
    }

    /** Chaves: empresa, contactos, documentos, apolice. {@code missing} = o que o servidor ainda exige nessa etapa. */
    public record Etapa(String key, boolean complete, List<String> missing) {
    }
}
