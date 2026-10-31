# Produção: aplicar a migração V2 (cadastro empresarial em etapas)

> **O guia de entrega completo e actual é [DEPLOY_V2_PRODUCAO.md](DEPLOY_V2_PRODUCAO.md)** (artefacto, variáveis, JAR,
> verificações e recuo). Este ficheiro fica como resumo da migração.

Passo a passo para levar o novo cadastro (`V2__cadastro_progressivo.sql`) à base de produção
(`jdbc:postgresql://db_kixima:5432/kixima`, o valor por omissão do `application.yml`). Visão geral do fluxo em
[cadastro-progressivo.md](cadastro-progressivo.md).

**O que a V2 faz** (nenhuma instrução apaga dados): acrescenta o valor `RASCUNHO` ao enum `CompanyStatus`; deixa
`companies.name` e `companies.tax_id` aceitarem NULL (só rascunhos); acrescenta `companies.submitted_at`,
`rejection_reason` e `onboarding_draft`; acrescenta `users.email_verified_at` (preenchida nos utilizadores que já
existem, para ninguém perder o acesso) com `DEFAULT CURRENT_TIMESTAMP`. Todas as instruções são idempotentes.

**Porque é preciso antes do novo JAR:** o Hibernate corre com `ddl-auto: validate`. Sem as colunas novas, o JAR
recusa arrancar com `Schema-validation: missing column [...]` (por exemplo `[onboarding_draft]`).

## Ordem recomendada

A V2 é compatível com o JAR antigo (colunas a mais, um valor de enum a mais e NOT NULL relaxado não o afectam).
Por isso, aplica-a **antes** de trocar o JAR e sem paragem de serviço.

1. Verificações prévias
2. Cópia de segurança
3. Aplicar a V2 (opção A à mão, ou opção B pelo Flyway)
4. Verificar a base
5. Configurar o email e o endereço público
6. Pôr o novo JAR
7. Verificações finais

## 1. Verificações prévias

```bash
# Contentor do Postgres no nó do Swarm onde corre (o nome muda com o deploy)
docker ps --filter name=db_kixima --format '{{.ID}}  {{.Names}}  {{.Image}}'
```

Confirma antes de continuar:

- A **base** é a de produção e tem os teus dados: `docker exec -i <ID> psql -U <utilizador> -d kixima -c '\dt'`
  lista as tabelas (`companies`, `users`, …). Se vier **vazia**, o volume está mal montado — ver
  [provisionamento-postgresql.md](provisionamento-postgresql.md), secção 8, e **pára aqui**.
- O utilizador com que vais correr o SQL é **dono** das tabelas `companies` e `users` e do tipo `CompanyStatus`
  (o `ALTER TYPE` e o `ALTER TABLE` exigem-no). Com o utilizador por omissão da imagem (`POSTGRES_USER`) é.
- Estado actual (antes): guarda estes números para comparar no fim.

```bash
docker exec -i <ID> psql -U <utilizador> -d kixima -Atc "
select 'users='||(select count(*) from users)||' companies='||(select count(*) from companies)||
       ' company_documents='||(select count(*) from company_documents);
select 'V2 já aplicada? coluna users.email_verified_at: '||
       coalesce((select 'sim' from information_schema.columns
                 where table_schema='public' and table_name='users' and column_name='email_verified_at'),'não');"
```

## 2. Cópia de segurança (obrigatória)

```bash
docker exec <ID> pg_dump -U <utilizador> -d kixima -Fc > kixima-antes-V2-$(date +%F).dump
ls -la kixima-antes-V2-*.dump          # tem de ter tamanho > 0
pg_restore --list kixima-antes-V2-*.dump | head   # confirma que o ficheiro é legível
```

Guarda o `.dump` **fora** do volume do Postgres (outra máquina ou outro disco). Não o juntes ao repositório: tem dados
reais.

## 3A. Aplicar a V2 à mão (recomendado)

Corre-se o ficheiro do repositório tal como está, numa só transacção e a parar ao primeiro erro:

```bash
docker exec -i <ID> psql -U <utilizador> -d kixima -v ON_ERROR_STOP=1 -1 \
  < src/main/resources/db/migration/V2__cadastro_progressivo.sql
```

Saída esperada: vários `ALTER TABLE`, `ALTER TYPE` e `UPDATE <n>` (n = número de utilizadores existentes),
terminando sem `ERROR`. Se houver erro, a transacção reverte e a base fica como estava.

Com esta opção o Flyway continua desligado e a base **não** fica com `flyway_schema_history`.

## 3B. Aplicar a V2 pelo Flyway (alternativa)

No serviço do backend, define `FLYWAY_ENABLED=true` (no ficheiro de ambiente do stack) e arranca o novo JAR.
Numa base que já tem o schema mas não tem histórico, o Flyway cria a `flyway_schema_history`, marca a base como V1
(`baseline-version: 1`) **sem repetir a V1** e corre só a V2. O utilizador da aplicação tem de ser dono das tabelas.
Se o arranque falhar a meio, o PostgreSQL reverte a V2 e o JAR não sobe: corrige a causa (ver os logs) e volta a tentar.

Depois de a base ficar migrada podes deixar `FLYWAY_ENABLED=true` (as migrações futuras aplicam-se sozinhas) ou voltar
a `false`.

## 4. Verificar a base

```bash
docker exec -i <ID> psql -U <utilizador> -d kixima -Atc "
select 'enum: '||string_agg(enumlabel,' < ' order by enumsortorder)
  from pg_enum e join pg_type t on t.oid=e.enumtypid where t.typname='CompanyStatus';
select 'companies.'||column_name||' nullable='||is_nullable from information_schema.columns
  where table_schema='public' and table_name='companies'
    and column_name in ('name','tax_id','submitted_at','rejection_reason','onboarding_draft') order by 1;
select 'users.email_verified_at default='||coalesce(column_default,'-') from information_schema.columns
  where table_schema='public' and table_name='users' and column_name='email_verified_at';
select 'users por confirmar (tem de ser 0): '||count(*) from users where email_verified_at is null;
select 'users='||(select count(*) from users)||' companies='||(select count(*) from companies)||
       ' company_documents='||(select count(*) from company_documents);"
```

Esperado: enum `RASCUNHO < PENDENTE < APROVADA < REJEITADA < SUSPENSA`; `name` e `tax_id` com `nullable=YES`; as três
colunas novas de `companies` existem; `email_verified_at` com default `CURRENT_TIMESTAMP`; **0** utilizadores por
confirmar; e as contagens iguais às do passo 1.

## 5. Email e endereço público (antes de abrir o registo)

O novo registo **depende de email**: sem o link de confirmação, nenhuma conta nova consegue entrar.

| Variável | Valor em produção |
|---|---|
| `EMAIL_PROVIDER` | `smtp` ou `brevo`. **Não** `console` (esse só escreve no log e o email nunca chega). |
| `EMAIL_FROM` | remetente, por exemplo `notificacoes@kixima.co.ao` |
| `BREVO_API_KEY` (brevo) ou `SMTP_HOST`/`SMTP_PORT`/`SMTP_USER`/`SMTP_PASSWORD` (smtp) | credenciais do provider |
| `APP_URL` | endereço público do site, por exemplo `https://app.kixima.co.ao`. Define o link do email de confirmação (e o da recuperação de senha). |

Põe estes valores no ficheiro de ambiente do stack (nunca no repositório) e não os escrevas em chats, tickets ou logs.
Se `APP_URL` ficar vazia, o link usa o cabeçalho `Host` do pedido, que pode ser forjado — define-a sempre.

## 6. Pôr o novo JAR

Troca a imagem/JAR do backend e deixa o Swarm reiniciar o serviço. O arranque falha com mensagem clara se faltar algo
exigido em produção (`JWT_SECRET` forte, `APP_URL` ou `CORS_ORIGINS`, storage S3…). Ver o README, secção de produção.

## 7. Verificações finais

1. `GET /ready` responde `ok`.
2. Entra com uma conta **que já existia** (por exemplo, o Admin do Sistema): continua a entrar, sem pedir confirmação
   de email.
3. No painel do Admin do Sistema, abre a **Prontidão**: as verificações «Envio de email» e «Endereço público
   (APP_URL)» têm de estar em OK.
4. Faz um registo de teste em `/cadastro` com uma caixa de correio **real**: o email chega, o link confirma, o login
   leva a `/onboarding`.
5. Um pedido a um endpoint de negócio com essa conta (por exemplo `GET /api/notifications`) responde
   `403 COMPANY_NOT_APPROVED` até à aprovação. Aprova-a em *Credenciamento* (due diligence) e confirma que abre o
   painel.
6. As empresas que estavam `PENDENTE` do cadastro antigo mantêm-se: o administrador delas passa a poder **entrar**
   para acompanhar o estado (antes era recusado), mas continua sem acesso ao negócio até à aprovação.

## Recuo

- **Não reverter o schema.** A V2 só acrescenta e relaxa. Voltar a pôr `NOT NULL` falha se já houver rascunhos, e um
  valor de enum não se retira de forma segura. Para recuar, recua-se **só a aplicação**.
- O JAR antigo ignora as colunas novas. Antes de recuar, vê se já há contas novas:

```sql
select count(*) from companies where status::text = 'RASCUNHO';   -- o JAR antigo não conhece este estado
select count(*) from users where email_verified_at is null;       -- o JAR antigo deixaria estas contas entrar sem confirmar
```

  Se os dois forem 0, o recuo é seguro. Se não, essas contas são registos novos: mantém o novo JAR, ou decide
  caso a caso o que fazer com elas (não as apagues sem copiar os dados).
- Se alguma coisa correr mal depois da V2, restaura o `.dump` do passo 2 só como último recurso e com o serviço
  parado: `pg_restore` por cima de uma base em uso pode perder o que foi escrito entretanto.

## Base de testes (a tua máquina)

A `kixima_test` é outra base, em `localhost`: aplica-lhe o mesmo ficheiro com `psql -d kixima_test -f
src/main/resources/db/migration/V2__cadastro_progressivo.sql` antes de `mvn test`. **Corre `mvn test` com
`DATABASE_URL_JDBC` por definir:** o `FlywayBaselineTest` usa-a para criar (e apagar) bases temporárias, e se apontar à
produção fá-lo no servidor de produção.
