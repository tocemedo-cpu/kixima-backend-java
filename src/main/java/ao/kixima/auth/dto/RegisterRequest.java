package ao.kixima.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registo em dois passos — 1.º passo: só o tipo de empresa (CLIENTE = Operadora, FORNECEDOR = Prestadora) e a conta do
 * administrador. Os dados e os documentos da empresa vêm depois, em {@code /api/onboarding}.
 */
public record RegisterRequest(@NotBlank String type, @NotBlank @Size(min = 2) String name, @NotBlank @Email String email,
                              @NotBlank String password, Boolean termsAccepted) {
}
