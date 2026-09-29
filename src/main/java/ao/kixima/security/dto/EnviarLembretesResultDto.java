package ao.kixima.security.dto;

import java.util.List;

/**
 * Espelha o objecto devolvido por mfaLembreteService.enviarLembretes.
 *
 * DIVERGÊNCIA DELIBERADA face ao Node: {@code falhas} fica sempre vazio no
 * Java — {@link ao.kixima.notification.EmailDispatchService#dispatch} nunca
 * lança (regista a falha no log e segue, o mesmo princípio usado em toda a
 * plataforma Java para notificações), ao contrário de
 * {@code notificationService.enviarEmailDireto} no Node, que propaga o erro
 * do fornecedor de email por item. O campo fica no contrato pela forma da
 * resposta; nunca é preenchido.
 */
public record EnviarLembretesResultDto(List<Enviado> enviados, List<Ignorado> ignorados,
                                        List<Falha> falhas, int total) {

    public record Enviado(String email, String nome) {
    }

    public record Ignorado(String email, String motivo) {
    }

    public record Falha(String email, String erro) {
    }
}
