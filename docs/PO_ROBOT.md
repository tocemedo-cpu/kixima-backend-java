# Automatic PO Robot — propostas de compra

Contrato HTTP com o frontend Vue: `docs/api-po-robot.md` do repositório do frontend. Este documento descreve o que o
backend faz e as regras de segurança que o código garante.

## Regras de segurança funcional

- O robot **só prepara propostas**. Nunca cria ordens de compra, nem aprova, nem rejeita POs, nem paga, nem submete
  propostas sem intervenção humana.
- Uma proposta só passa a compra quando um `COMPRADOR` ou `COMPANY_ADMIN` da mesma empresa a **submete**
  (`POST /api/po-robot/propostas/{id}/submeter`). A PO é criada pelo `PoService` (sem via paralela), em nome de quem
  submeteu, com `createdBySource = ROBOT`, e nasce em `AGUARDANDO_APROVACAO`. A aprovação e o pagamento seguem o fluxo normal.
- Tudo é da empresa da sessão (`companyId`). Uma proposta, regra ou execução de outra empresa responde `404`.
- Exige o add-on `PO_ROBOT` activo nas operações de escrita (regras, ajustar, submeter, rejeitar).
- Não há endpoint de aprovação nem de pagamento neste módulo.

## Endpoints (`/api/po-robot`, perfis `COMPRADOR` e `COMPANY_ADMIN`)

| Método e caminho | Notas |
|---|---|
| `GET /regras` | Com produto e fornecedora; inclui `stockMinimo`, `stockSeguranca`, `prazoEntregaDias`, `ultimaExecucaoEm` |
| `POST /regras` | `422` se o corpo for inválido; `409` se a empresa já tiver regra para o produto |
| `PUT /regras/{id}` | Campo ausente mantém; `null` limpa (quantidade, limite, stocks, prazo) |
| `DELETE /regras/{id}` | `204`. Propostas e previsões ficam no histórico (`regraId` passa a `null`) |
| `GET /media-sugerida/{productId}` | Média do histórico de compras (12 meses); devolve `productId` e `periodoMeses` e mantém os campos antigos |
| `GET /previsoes` | A última previsão de cada regra existente |
| `GET /propostas` | Todas as da empresa, da mais recente para a mais antiga |
| `PUT /propostas/{id}` | `{ quantidade }` inteira > 0; só em `PENDENTE_REVISAO` (senão `409`); não pode ultrapassar o limite da regra |
| `POST /propostas/{id}/submeter` | Só em `PENDENTE_REVISAO`; cria a PO; segunda submissão `409` |
| `POST /propostas/{id}/rejeitar` | `{ motivo }` obrigatório (até 500 caracteres) |
| `GET /execucoes` | Até 100, da mais recente |
| `GET /actividades` | Até 200 eventos, lidos de `audit_logs` da empresa; `autor: null` = o robot |

## Ciclo agendado

`PoRoboJob` (06:00, **desligado por omissão**: `KIXIMA_JOB_PO_ROBOT_ENABLED=true` para ligar). Por empresa cria uma
execução (`EM_CURSO` → `CONCLUIDA`, `COM_ERROS` ou `FALHOU`) e, por regra devida:

1. calcula a previsão, com a justificação passo a passo. `stockActual` é sempre `null`: não existe fonte fiável do
   inventário do comprador e o stock do fornecedor nunca o substitui. A quantidade segue o consumo médio
   (a fixa da regra sobrepõe-se; a média escala pela periodicidade; mínimo 1);
2. aplica o **limite de segurança**: reduz a quantidade ao máximo que cabe, guarda `quantidadeOriginal` e marca
   `limiteAplicado`. Se nem uma unidade couber, **não há proposta** e o motivo fica nos `erros` da execução;
3. expira a proposta pendente anterior da mesma regra (`EXPIRADA`) — a base garante no máximo uma pendente por regra;
4. cria a proposta `PENDENTE_REVISAO` e avança a próxima execução.

Uma regra que falha por razão técnica (produto inactivo, add-on caducado…) não aborta as outras: regista o erro, repõe
a data e é tentada no ciclo seguinte.

## Base de dados — `V4__po_robot_propostas.sql`

Aditiva e idempotente; não toca nas V1–V3. Acrescenta 4 colunas a `po_robo_regras` e cria `po_robo_execucoes`,
`po_robo_previsoes` e `po_robo_propostas` (índice único parcial `po_robo_propostas_regra_pendente_uq`). A justificação e
os erros guardam-se como JSON em texto.
