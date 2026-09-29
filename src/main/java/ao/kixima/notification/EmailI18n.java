package ao.kixima.notification;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Espelha backend/src/i18n/emails.js — traduções das mensagens que o SERVIDOR
 * envia por email.
 *
 * Porquê existir: o idioma escolhido na plataforma vivia só no localStorage do
 * browser. O servidor não o vê — e é o servidor que escreve o convite, o link de
 * recuperação de senha e o aviso de fatura pendente. Resultado: um utilizador
 * francês recebia tudo em português.
 *
 * A chave é o texto em português, como no frontend. Sem tradução, devolve o
 * original — nunca falha, no pior caso fica em PT. Dicionários copiados
 * literalmente do Node (mesmas chaves, mesmos textos, mesmos marcadores).
 */
public final class EmailI18n {

    private static final String PT = "pt";

    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("Nova ordem de compra recebida", "New purchase order received"),
            Map.entry("Recebeu a ordem de compra {ref}. Reveja e aceite ou recuse.",
                    "You have received purchase order {ref}. Review and accept or decline it."),
            Map.entry("Fatura pendente de pagamento", "Invoice pending payment"),
            Map.entry("A fatura {fatura} (PO {po}) foi gerada. Prazo de pagamento: {prazo}.",
                    "Invoice {fatura} (PO {po}) has been issued. Payment due: {prazo}."),
            Map.entry("Pagamento recebido", "Payment received"),
            Map.entry("O pagamento da PO {ref} foi processado. Pode iniciar a execução/entrega.",
                    "Payment for PO {ref} has been processed. You may start execution/delivery."),
            Map.entry("Entrega despachada", "Delivery dispatched"),
            Map.entry("Convite para a KIXIMA", "Invitation to KIXIMA"),
            Map.entry("Recuperação de senha", "Password recovery"),
            Map.entry("Nova candidatura ao Supplier Development", "New Supplier Development application"),
            Map.entry("Código de acesso KIXIMA", "KIXIMA access code"),
            Map.entry("Código para ativar a verificação em dois passos", "Code to turn on two-step verification"),
            Map.entry("O seu código é {codigo}. É válido durante {minutos} minutos e só pode ser usado uma vez. Se não foi você a pedi-lo, alguém sabe a sua senha — mude-a assim que puder.",
                    "Your code is {codigo}. It is valid for {minutos} minutes and can only be used once. If you did not request it, someone knows your password — change it as soon as you can."),
            Map.entry("Falta ativar a verificação em dois passos", "Two-step verification is still not on"),
            Map.entry("A sua conta KIXIMA aprova operações com dinheiro, por isso a senha deixou de bastar. Falta ativar a verificação em dois passos.",
                    "Your KIXIMA account approves money operations, so a password is no longer enough. Two-step verification is still not turned on."),
            Map.entry("A partir de {data}, sem isto configurado a sua conta só dá acesso ao ecrã de ativação — não conseguirá aprovar ordens nem consultar o resto da plataforma.",
                    "From {data}, without this set up your account will only reach the activation screen — you will not be able to approve orders or view the rest of the platform."),
            Map.entry("Entre na plataforma e vá a Configurações → Segurança. Demora menos de um minuto: enviamos-lhe um código por email e é só confirmá-lo.",
                    "Sign in and go to Settings → Security. It takes less than a minute: we email you a code and you just confirm it."),
            Map.entry("Apólice atualizada", "Policy updated"),
            Map.entry("Subscrição a vencer", "Subscription expiring soon"),
            Map.entry("A subscrição da sua empresa vence em 30 dias.", "Your company subscription expires in 30 days."),
            Map.entry("A subscrição da sua empresa vence em 7 dias. Renove para continuar a utilizar todos os recursos do plano.",
                    "Your company subscription expires in 7 days. Renew to keep using all of your plan's features."),
            Map.entry("A subscrição da sua empresa vence em 3 dias. Renove para não perder acesso aos recursos pagos.",
                    "Your company subscription expires in 3 days. Renew to avoid losing access to paid features."),
            Map.entry("A subscrição da sua empresa vence amanhã. Renove hoje para não interromper o serviço.",
                    "Your company subscription expires tomorrow. Renew today to avoid a service interruption."),
            Map.entry("A subscrição da sua empresa vence hoje. Envie o comprovativo de pagamento para não interromper o serviço.",
                    "Your company subscription expires today. Send the payment proof to avoid a service interruption."),
            Map.entry("A subscrição da sua empresa expirou. Os seus dados continuam seguros — envie o comprovativo de pagamento para renovar o acesso aos recursos pagos.",
                    "Your company subscription has expired. Your data remains safe — send the payment proof to restore access to paid features."),
            Map.entry("A subscrição da sua empresa continua por regularizar. Os seus dados continuam seguros, mas os recursos pagos ficam indisponíveis em breve sem renovação.",
                    "Your company subscription is still unpaid. Your data remains safe, but paid features will become unavailable soon without renewal.")
    );

    private static final Map<String, String> FR = Map.ofEntries(
            Map.entry("Nova ordem de compra recebida", "Nouveau bon de commande reçu"),
            Map.entry("Recebeu a ordem de compra {ref}. Reveja e aceite ou recuse.",
                    "Vous avez reçu le bon de commande {ref}. Vérifiez-le puis acceptez ou refusez."),
            Map.entry("Fatura pendente de pagamento", "Facture en attente de paiement"),
            Map.entry("A fatura {fatura} (PO {po}) foi gerada. Prazo de pagamento: {prazo}.",
                    "La facture {fatura} (BC {po}) a été émise. Échéance de paiement : {prazo}."),
            Map.entry("Pagamento recebido", "Paiement reçu"),
            Map.entry("O pagamento da PO {ref} foi processado. Pode iniciar a execução/entrega.",
                    "Le paiement du BC {ref} a été traité. Vous pouvez lancer l’exécution/la livraison."),
            Map.entry("Entrega despachada", "Livraison expédiée"),
            Map.entry("Convite para a KIXIMA", "Invitation à KIXIMA"),
            Map.entry("Recuperação de senha", "Récupération de mot de passe"),
            Map.entry("Nova candidatura ao Supplier Development", "Nouvelle candidature au Supplier Development"),
            Map.entry("Código de acesso KIXIMA", "Code d’accès KIXIMA"),
            Map.entry("Código para ativar a verificação em dois passos", "Code pour activer la vérification en deux étapes"),
            Map.entry("O seu código é {codigo}. É válido durante {minutos} minutos e só pode ser usado uma vez. Se não foi você a pedi-lo, alguém sabe a sua senha — mude-a assim que puder.",
                    "Votre code est {codigo}. Il est valable {minutos} minutes et ne peut servir qu’une seule fois. Si vous ne l’avez pas demandé, quelqu’un connaît votre mot de passe — changez-le dès que possible."),
            Map.entry("Falta ativar a verificação em dois passos", "La vérification en deux étapes n’est toujours pas activée"),
            Map.entry("A sua conta KIXIMA aprova operações com dinheiro, por isso a senha deixou de bastar. Falta ativar a verificação em dois passos.",
                    "Votre compte KIXIMA approuve des opérations financières, le mot de passe ne suffit donc plus. La vérification en deux étapes n’est toujours pas activée."),
            Map.entry("A partir de {data}, sem isto configurado a sua conta só dá acesso ao ecrã de ativação — não conseguirá aprovar ordens nem consultar o resto da plataforma.",
                    "À partir du {data}, sans cette configuration votre compte n’accédera qu’à l’écran d’activation — vous ne pourrez ni approuver de bons de commande ni consulter le reste de la plateforme."),
            Map.entry("Entre na plataforma e vá a Configurações → Segurança. Demora menos de um minuto: enviamos-lhe um código por email e é só confirmá-lo.",
                    "Connectez-vous et allez dans Paramètres → Sécurité. Cela prend moins d’une minute : nous vous envoyons un code par e-mail, il suffit de le confirmer."),
            Map.entry("Apólice atualizada", "Police mise à jour"),
            Map.entry("Subscrição a vencer", "Abonnement bientôt expiré"),
            Map.entry("A subscrição da sua empresa vence em 30 dias.", "L’abonnement de votre entreprise expire dans 30 jours."),
            Map.entry("A subscrição da sua empresa vence em 7 dias. Renove para continuar a utilizar todos os recursos do plano.",
                    "L’abonnement de votre entreprise expire dans 7 jours. Renouvelez pour continuer à profiter de toutes les fonctionnalités de votre forfait."),
            Map.entry("A subscrição da sua empresa vence em 3 dias. Renove para não perder acesso aos recursos pagos.",
                    "L’abonnement de votre entreprise expire dans 3 jours. Renouvelez pour ne pas perdre l’accès aux fonctionnalités payantes."),
            Map.entry("A subscrição da sua empresa vence amanhã. Renove hoje para não interromper o serviço.",
                    "L’abonnement de votre entreprise expire demain. Renouvelez aujourd’hui pour éviter une interruption de service."),
            Map.entry("A subscrição da sua empresa vence hoje. Envie o comprovativo de pagamento para não interromper o serviço.",
                    "L’abonnement de votre entreprise expire aujourd’hui. Envoyez le justificatif de paiement pour éviter une interruption de service."),
            Map.entry("A subscrição da sua empresa expirou. Os seus dados continuam seguros — envie o comprovativo de pagamento para renovar o acesso aos recursos pagos.",
                    "L’abonnement de votre entreprise a expiré. Vos données restent en sécurité — envoyez le justificatif de paiement pour retrouver l’accès aux fonctionnalités payantes."),
            Map.entry("A subscrição da sua empresa continua por regularizar. Os seus dados continuam seguros, mas os recursos pagos ficam indisponíveis em breve sem renovação.",
                    "L’abonnement de votre entreprise reste à régulariser. Vos données restent en sécurité, mais les fonctionnalités payantes seront bientôt indisponibles sans renouvellement.")
    );

    private static final Map<String, Map<String, String>> DICT = Map.of("en", EN, "fr", FR);

    public static final List<String> IDIOMAS = List.of("pt", "en", "fr");

    private EmailI18n() {
    }

    /** Normaliza o que vier da base ("EN", "fr-FR", null) para um dos três idiomas. */
    public static String normalizar(String locale) {
        String l = (locale == null ? "" : locale).toLowerCase(Locale.ROOT);
        l = l.substring(0, Math.min(2, l.length()));
        return IDIOMAS.contains(l) ? l : PT;
    }

    /**
     * Traduz uma mensagem para o idioma do destinatário.
     * @param texto  texto em português (é a chave)
     * @param locale idioma do utilizador ('pt' | 'en' | 'fr')
     */
    public static String t(String texto, String locale) {
        return t(texto, locale, null);
    }

    /**
     * @param vars substituições {chave} → valor, aplicadas em qualquer idioma
     *             (também em PT, tal como no Node).
     */
    public static String t(String texto, String locale, Map<String, ?> vars) {
        String lang = normalizar(locale);
        String s = texto;
        if (!PT.equals(lang)) {
            String traduzido = DICT.get(lang) == null ? null : DICT.get(lang).get(texto);
            if (traduzido != null) s = traduzido;
        }
        if (vars != null && s != null) {
            for (Map.Entry<String, ?> e : vars.entrySet()) {
                s = s.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
            }
        }
        return s;
    }
}
