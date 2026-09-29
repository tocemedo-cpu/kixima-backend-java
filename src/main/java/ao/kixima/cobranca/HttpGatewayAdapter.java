package ao.kixima.cobranca;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Adaptador HTTP genérico que espelha a forma comum dos três ficheiros do Node
 * (EMIS, PayPay, bancos): pedido com referência/valor/moeda em cêntimos
 * inteiros, e confirmação que volta a perguntar ao gateway pela transação.
 * Cada canal difere só nos caminhos, nos campos extra e nos estados que
 * significam "pago" — ver {@link CanaisPagamentoService}.
 */
public class HttpGatewayAdapter implements GatewayAdapter {

    /** Uma variável de ambiente exigida: o nome que o Node reporta em "Em falta" e o valor lido. */
    /** {@code campo} é a chave do CONFIG do serviço Node (baseUrl, posId…): é o que `emFalta` expõe; {@code nomeEnv} vai na mensagem de erro. */
    public record Credencial(String nomeEnv, String campo, String valor) {
        boolean ausente() {
            return valor == null || valor.isBlank();
        }
    }

    public record Definicao(String canal, String nome, String canalNoEstado, String caminhoPedido, String caminhoTransacao,
                            Set<String> estadosPagos, String origem, String notaPorLigar, String erroRecusa,
                            String moedaExigida) {
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Definicao def;
    private final String baseUrl;
    private final String token;
    private final List<Credencial> credenciais;
    private final Consumer<Map<String, Object>> camposExtra;

    public HttpGatewayAdapter(Definicao def, String baseUrl, String token, List<Credencial> credenciais,
                              Consumer<Map<String, Object>> camposExtra) {
        this.def = def;
        this.baseUrl = baseUrl;
        this.token = token;
        this.credenciais = credenciais;
        this.camposExtra = camposExtra;
    }

    @Override
    public String canal() {
        return def.canal();
    }

    @Override
    public List<String> emFalta() {
        List<String> falta = new ArrayList<>();
        for (Credencial c : credenciais) if (c.ausente()) falta.add(c.campo());
        return falta;
    }

    private void exigirConfiguracao() {
        List<String> falta = new ArrayList<>();
        for (Credencial c : credenciais) if (c.ausente()) falta.add(c.nomeEnv());
        if (!falta.isEmpty()) {
            throw new IllegalStateException(def.nome() + " não está configurado. Em falta: " + String.join(", ", falta)
                    + ". Este canal não funciona sem credenciais " + artigo() + def.nome() + " — e não simula pagamentos.");
        }
    }

    private String artigo() {
        return def.canal().equals("EMIS_MULTICAIXA") || def.canal().equals("PAYPAY") ? "da " : "do ";
    }

    @Override
    public Map<String, Object> estado() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("canal", def.canalNoEstado());
        m.put("disponivel", disponivel());
        m.put("emFalta", emFalta());
        m.put("nota", disponivel() ? "Configurado." : def.notaPorLigar());
        return m;
    }

    @Override
    public Map<String, Object> pedirPagamento(PedidoPagamento pedido) {
        exigirConfiguracao();
        if (def.moedaExigida() != null && !def.moedaExigida().equals(pedido.moeda())) {
            // O Multicaixa é kwanza — um documento noutra moeda tem de ir por outro canal, nunca convertido em silêncio.
            throw new IllegalStateException("O Multicaixa Express só liquida em AOA; este documento está em " + pedido.moeda() + ".");
        }
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("reference", pedido.referencia());
        // Em cêntimos e como INTEIRO — decimais numa API de pagamentos são arredondamentos que ninguém vê.
        corpo.put("amount", pedido.valor().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact());
        corpo.put("currency", pedido.moeda());
        if (pedido.telemovel() != null) corpo.put("phone", pedido.telemovel());
        camposExtra.accept(corpo);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + def.caminhoPedido()))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(corpo))).build();
            HttpResponse<String> resposta = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resposta.statusCode() < 200 || resposta.statusCode() >= 300) {
                throw new IllegalStateException(def.erroRecusa().replace("{status}", String.valueOf(resposta.statusCode())));
            }
            return JSON.readValue(resposta.body(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Falha a contactar " + def.nome() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Verificacao confirmarCallback(Map<String, Object> payload) {
        exigirConfiguracao();
        Object id = payload == null ? null : payload.get("id") != null ? payload.get("id") : payload.get("transactionId");
        if (id == null || String.valueOf(id).isBlank()) throw new IllegalStateException("Callback sem identificador de transação.");
        String idTransacao = String.valueOf(id);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + def.caminhoTransacao() + URLEncoder.encode(idTransacao, StandardCharsets.UTF_8)))
                    .header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> resposta = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resposta.statusCode() < 200 || resposta.statusCode() >= 300) {
                throw new IllegalStateException("Não foi possível confirmar a transação " + idTransacao + " junto " + artigo() + def.nome() + ".");
            }
            Map<String, Object> verdade = JSON.readValue(resposta.body(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
            Object amount = verdade.get("amount");
            BigDecimal montante = amount == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(amount)).divide(BigDecimal.valueOf(100));
            return new Verificacao(idTransacao, def.estadosPagos().contains(String.valueOf(verdade.get("status"))), montante,
                    verdade.get("reference") == null ? null : String.valueOf(verdade.get("reference")), def.origem());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Falha a contactar " + def.nome() + ": " + e.getMessage(), e);
        }
    }
}
