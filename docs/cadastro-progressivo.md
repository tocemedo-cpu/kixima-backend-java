# Cadastro empresarial em etapas

O registo deixou de ser um formulário único. A **conta** (utilizador) e a **empresa** continuam separadas:
primeiro cria-se a conta, depois a empresa vai sendo completada, e só a aprovação da KIXIMA liberta as
funcionalidades de negócio. Os dados e documentos pedidos são os de sempre — só mudou o momento.

## Fluxo

1. **Tipo de empresa** — `CLIENTE` (Operadora) ou `FORNECEDOR` (Prestadora).
2. **Conta** — `POST /api/auth/register` `{ type, name, email, password, termsAccepted }`. Cria a empresa em
   `RASCUNHO` (sem nome nem NIF) e o utilizador `COMPANY_ADMIN`, com o email por confirmar. Envia o email de confirmação.
3. **Confirmar o email** — `POST /api/auth/verify-email` `{ token }` (link válido 48 h, idempotente);
   `POST /api/auth/resend-verification` `{ email }` (resposta sempre igual). Sem confirmar, o login responde
   `403 EMAIL_NOT_VERIFIED` (depois de a senha estar certa).
4. **Entrar** — o login passa a aceitar empresas `RASCUNHO`, `PENDENTE` e `REJEITADA` (só `SUSPENSA` continua
   recusada). A sessão e `/api/auth/me` trazem `companyStatus`.
5. **Cadastro progressivo** — `/api/onboarding` (só `COMPANY_ADMIN`):
   * `GET` — dados guardados, documentos já enviados, etapas (`empresa`, `contactos`, `documentos`, `apolice`
     nas Prestadoras), `pendencias`, `podeSubmeter`, `editable`, `rejectionReason`.
   * `PUT` — guarda parcialmente (`null` = não toca, `""` = limpa). Só valida formato; não exige o conjunto.
   * `POST /documents/{tipo}` (multipart, campo `file`) — `CERTIDAO_COMERCIAL`, `ALVARA_COMERCIAL`, `LICENCA_ANPG`
     (os exigidos ao tipo de empresa), `APOLICE_SEGURO` (Prestadoras) e `LOGOTIPO` (opcional). Reenviar substitui.
   * `POST /submit` — aplica as regras de negócio (nome e NIF, documentos obrigatórios por tipo, apólice das
     Prestadoras) e passa a `PENDENTE`.
6. **Decisão** — `PATCH /api/companies/{id}/decision` (Admin do Sistema, área CADASTRO): `APROVADA` ou
   `REJEITADA` (+ `rejectionReason`). Um rascunho não se decide. Uma empresa `REJEITADA` volta a poder editar e submeter.

## Estados da empresa

`RASCUNHO` → (submeter) → `PENDENTE` → (KIXIMA) → `APROVADA` | `REJEITADA` → (corrigir e submeter) → `PENDENTE`.
`SUSPENSA` não muda.

## Portão no servidor

`CadastroGate`, no `AuthenticationFilter`: enquanto a empresa não está `APROVADA`, o utilizador só chega a
`/api/auth/**` e `/api/onboarding/**`. Tudo o resto devolve `403 COMPANY_NOT_APPROVED` — antes de qualquer
controller, por isso um endpoint novo fica protegido por omissão. Lê o estado a cada pedido: a aprovação (ou a
rejeição) vale logo, sem novo login.

## Ficheiros

`/api/uploads/*`: o logótipo é público; os documentos de credenciamento e a apólice só abrem à própria empresa e à
KIXIMA (`UploadAccessService`).

## Base de dados — migração `V2__cadastro_progressivo.sql`

* `CompanyStatus` ganha `RASCUNHO`; `companies.name` e `companies.tax_id` passam a aceitar `NULL` (só em rascunho).
* `companies.submitted_at`, `rejection_reason`, `onboarding_draft` (JSON com a apólice ainda por submeter).
* `users.email_verified_at` — as contas existentes ficam confirmadas, e o valor por omissão é `CURRENT_TIMESTAMP`
  (quem insere utilizadores sem conhecer a coluna, como o backend Node que partilha a base, cria contas confirmadas;
  só o registo em dois passos escreve `NULL`).

**Aplicar a V2**: com `FLYWAY_ENABLED=true` aplica-se sozinha (em bases existentes marcadas como V1, corre só a V2).
Com o Flyway desligado, o Hibernate (`ddl-auto: validate`) recusa arrancar sem as colunas — aplicar o ficheiro à mão
(`psql -f V2__cadastro_progressivo.sql`) **também na base de testes** (`kixima_test`) antes de `mvn test`.

## Compatibilidade

`POST /api/companies/register` (cadastro de uma só vez) mantém-se funcional: cria a empresa em `PENDENTE` e o
administrador já confirmado, que agora também pode entrar para acompanhar o estado. O frontend deixou de o usar.
