# Entrega V2 do backend KIXIMA: guia para o responsável pela produção

Esta versão traz o **cadastro empresarial em etapas** (conta com email confirmado, perfil da empresa preenchido
por etapas, submissão para aprovação da KIXIMA, e bloqueio no servidor das funcionalidades de negócio até à
aprovação). Visão funcional: [cadastro-progressivo.md](cadastro-progressivo.md).

**Resumo do que tem de acontecer, por esta ordem:**

1. Fazer **backup** da base `kixima`.
2. Aplicar a **migração V2** à base (ou deixar o Flyway aplicá-la).
3. Configurar **email** (`EMAIL_PROVIDER`, `EMAIL_FROM`, credenciais) e **`APP_URL`**.
4. Trocar o **JAR** e reiniciar o serviço.
5. Fazer as **verificações finais**.

A V2 só acrescenta e relaxa restrições, não apaga dados, e o JAR antigo continua a funcionar com ela já aplicada.
Por isso pode aplicar-se **antes** de trocar o JAR, sem paragem de serviço.

> **Regra de ouro:** nada neste guia deve ser corrido contra a base de produção a partir de uma máquina de
> desenvolvimento, e nenhum valor secreto (senhas, chaves, `JWT_SECRET`) deve ser escrito neste ficheiro, em
> tickets, em chats ou em logs.

---

## 0. O artefacto entregue

| | |
|---|---|
| Ficheiro | `kixima-backend-java-0.1.0-SNAPSHOT.jar` |
| Origem | commit `7c5a3d9` (`main`), árvore de trabalho limpa |
| Tamanho | 94 777 543 bytes (~91 MB) |
| Gerado em | 2026-10-31 13:42 UTC |
| Java | compilado para Java 21 (corre em Java 21 ou superior) |
| SHA-256 | `23e675d6e0662463c38754aa487434b0e40f7697728558f29dc22a0ed925e358` |
| Comando de geração | `mvn -B package -DskipTests` (o mesmo do `Dockerfile`; o perfil `prod` é **de execução**, não de build) |

Confirma a integridade antes de usar:

```bash
sha256sum kixima-backend-java-0.1.0-SNAPSHOT.jar
# tem de coincidir exactamente com o SHA-256 acima
```

> **Versões do JAR e o Flyway.** Este JAR (commit `7c5a3d9`) tem o Flyway **desligado por omissão**, também em `prod`:
> a V2 aplica-se à mão (3A) ou com `FLYWAY_ENABLED=true` (3B). Os JAR compilados **depois** da correcção «Flyway ligado
> por omissão em `prod` e no perfil de testes» (ver secção 7b) aplicam as migrações sozinhos ao arrancar; neles, para
> aplicar à mão (3A), define `FLYWAY_ENABLED=false`.

Qualquer nova compilação gera um hash diferente. Se o JAR for reconstruído, o hash desta tabela deixa de valer e deve
usar-se o do ficheiro realmente entregue.

A V2 vai **dentro** do JAR (`BOOT-INF/classes/db/migration/V2__cadastro_progressivo.sql`) e também está no
repositório em `src/main/resources/db/migration/V2__cadastro_progressivo.sql`. Os dois são o mesmo ficheiro.

---

## 1. Verificações prévias

Faz isto no servidor onde corre o PostgreSQL de produção (nó do Swarm com o serviço `db_kixima`).

### 1.1. Localizar o contentor do Postgres

```bash
docker ps --filter name=db_kixima --format '{{.ID}}  {{.Names}}  {{.Image}}'
```

Nos comandos seguintes, `<ID>` é o ID do contentor e `<utilizador>` é o `POSTGRES_USER` do serviço
(por omissão `kixima`). Não escrevas a senha em comandos: dentro do contentor, a ligação local por socket não a pede.

### 1.2. Confirmar que é a base `kixima` e que tem dados

```bash
docker exec -i <ID> psql -U <utilizador> -d kixima -Atc "select current_database();"      # tem de devolver: kixima
docker exec -i <ID> psql -U <utilizador> -d kixima -c '\dt' | head -20                      # companies, users, ...
```

**Pára aqui se `\dt` vier vazio.** Uma base sem tabelas quase sempre quer dizer que o volume do Postgres está mal
montado (ver [provisionamento-postgresql.md](provisionamento-postgresql.md), secção 8) e a base «verdadeira» está
noutro sítio. Corrige isso primeiro; não apliques a V2 numa base vazia.

### 1.3. Confirmar que o utilizador é dono das tabelas

```bash
docker exec -i <ID> psql -U <utilizador> -d kixima -Atc "
select 'dono companies: '||tableowner from pg_tables where schemaname='public' and tablename='companies';
select 'dono users: '||tableowner from pg_tables where schemaname='public' and tablename='users';
select 'dono do tipo CompanyStatus: '||pg_get_userbyid(typowner) from pg_type where typname='CompanyStatus';"
```

O `ALTER TYPE` e o `ALTER TABLE` da V2 exigem ser dono (ou superutilizador). Com o utilizador por omissão da imagem é.

### 1.4. Registar o estado «antes» e ver se a V2 já está aplicada

```bash
docker exec -i <ID> psql -U <utilizador> -d kixima -Atc "
select 'users='||(select count(*) from users)||' companies='||(select count(*) from companies)||
       ' company_documents='||(select count(*) from company_documents);
select 'V2 já aplicada (users.email_verified_at existe)? '||
       case when exists (select 1 from information_schema.columns
            where table_schema='public' and table_name='users' and column_name='email_verified_at')
            then 'sim' else 'não' end;"
```

Guarda estes números: comparam-se no passo 3.

---

## 2. Backup obrigatório

**Não avances sem backup legível.**

```bash
docker exec <ID> pg_dump -U <utilizador> -d kixima -Fc > kixima-antes-V2-$(date +%F).dump
ls -la kixima-antes-V2-*.dump                       # tamanho > 0
pg_restore --list kixima-antes-V2-*.dump | head     # confirma que o ficheiro é legível
```

- Guarda o `.dump` **fora** do volume do Postgres (outra máquina ou outro disco).
- O ficheiro tem dados reais: não o juntes ao repositório nem o envies por canais abertos.
- Restaurar é um último recurso (ver «Recuo»): `pg_restore` por cima de uma base em uso pode perder o que foi escrito
  entretanto.

---

## 3. Aplicar a migração V2

Há dois caminhos. **Recomendado: aplicação manual (3A)**, porque deixa o controlo e o resultado visíveis.

### 3A. Aplicação manual com `psql` (recomendado)

A partir da pasta que contém o repositório (ou uma cópia do ficheiro SQL):

```bash
docker exec -i <ID> psql -U <utilizador> -d kixima -v ON_ERROR_STOP=1 -1 \
  < src/main/resources/db/migration/V2__cadastro_progressivo.sql
```

- `-d kixima`: confirma que é a base certa **antes** de carregar em Enter.
- `-v ON_ERROR_STOP=1`: para ao primeiro erro.
- `-1`: tudo numa só transacção. Se algo falhar, **nada** fica aplicado.

Saída esperada: vários `ALTER TABLE` e `ALTER TYPE`, mais um `UPDATE <n>` (n = número de utilizadores existentes),
sem nenhuma linha `ERROR`. Repetir o comando é seguro (todas as instruções são idempotentes; da segunda vez o
`UPDATE` devolve `0`).

Neste caminho o Flyway fica desligado (`FLYWAY_ENABLED=false`) e a base **não** passa a ter a tabela
`flyway_schema_history`. (Num JAR em que o Flyway vem ligado por omissão em `prod`, deixar `FLYWAY_ENABLED` por definir
também é seguro depois da aplicação manual: o Flyway marca a base como V1 e repete a V2, que é idempotente.)

### 3B. Aplicação pelo Flyway (alternativa)

No serviço do backend define `FLYWAY_ENABLED=true` e arranca o novo JAR. Numa base que já tem o schema mas não tem
histórico, o Flyway cria a `flyway_schema_history`, marca a base como V1 (`baseline-version: 1`) **sem repetir a V1**
e corre só a V2. O utilizador da aplicação tem de ser dono das tabelas (ver 1.3). Se a V2 falhar, o PostgreSQL reverte-a
e o JAR não arranca: a causa fica nos logs.

(Testado com este JAR: numa base com o schema antigo, o Flyway regista `1 BASELINE` e `2 SQL`, e o serviço fica pronto.)

---

## 4. Verificar a base

```bash
docker exec -i <ID> psql -U <utilizador> -d kixima -Atc "
select 'enum CompanyStatus: '||string_agg(enumlabel, ' < ' order by enumsortorder)
  from pg_enum e join pg_type t on t.oid=e.enumtypid where t.typname='CompanyStatus';
select 'companies.'||column_name||' | '||data_type||' | nullable='||is_nullable
  from information_schema.columns where table_schema='public' and table_name='companies'
   and column_name in ('name','tax_id','submitted_at','rejection_reason','onboarding_draft') order by column_name;
select 'users.'||column_name||' | '||data_type||' | nullable='||is_nullable||' | default='||coalesce(column_default,'-')
  from information_schema.columns where table_schema='public' and table_name='users' and column_name='email_verified_at';
select 'unique em tax_id mantido: '||count(*) from pg_indexes
  where schemaname='public' and tablename='companies' and indexdef ilike '%unique%' and indexdef ilike '%tax_id%';
select 'users por confirmar (tem de ser 0): '||count(*) from users where email_verified_at is null;
select 'users='||(select count(*) from users)||' companies='||(select count(*) from companies)||
       ' company_documents='||(select count(*) from company_documents);"
```

**Resultado esperado:**

| Verificação | Esperado |
|---|---|
| enum `CompanyStatus` | `RASCUNHO < PENDENTE < APROVADA < REJEITADA < SUSPENSA` |
| `companies.name`, `companies.tax_id` | `nullable=YES` |
| `companies.submitted_at` | `timestamp without time zone`, `nullable=YES` |
| `companies.rejection_reason` | `text`, `nullable=YES` |
| `companies.onboarding_draft` | `text`, `nullable=YES` |
| `users.email_verified_at` | `timestamp without time zone`, `nullable=YES`, `default=CURRENT_TIMESTAMP` |
| índice único em `tax_id` | `1` |
| utilizadores por confirmar | `0` |
| contagens | **iguais** às do passo 1.4 |

Se alguma linha não bater, **não avances**: confirma o backup e investiga.

---

## 5. Configuração do serviço (variáveis de ambiente)

Põe estes valores no **ficheiro de ambiente / segredos do stack** (por exemplo o `env_file` do serviço). Nunca no
repositório, nunca neste guia.

### 5.1. Obrigatórias em produção (o arranque recusa-se a subir sem elas)

| Variável | Valor / nota |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod`. Sem isto o serviço arranca com valores de desenvolvimento. |
| `DATABASE_URL_JDBC` | `jdbc:postgresql://db_kixima:5432/kixima`. No perfil `prod` **não há valor por omissão**: tem de estar definida. |
| `DATABASE_USER` | utilizador da aplicação na base `kixima` |
| `DATABASE_PASSWORD` | senha desse utilizador (**segredo**) |
| `JWT_SECRET` | segredo aleatório com **pelo menos 32 caracteres** (**segredo**). Valores de exemplo são recusados. |
| `APP_URL` | endereço público do site, p. ex. `https://app.kixima.co.ao` (sem barra final). |
| `STORAGE_PROVIDER` | `s3` (obrigatório em produção) |
| `STORAGE_BUCKET`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` | credenciais do armazenamento S3-compatível (**segredos**); `STORAGE_REGION` e, se não for AWS (MinIO, Supabase, R2…), `STORAGE_ENDPOINT` (e `STORAGE_FORCE_PATH_STYLE=true` quando o fornecedor o exigir; ver README) |

### 5.2. Email — novo e crítico nesta versão

O registo novo **depende de email**: o utilizador só entra depois de abrir o link de confirmação. Se o email não chegar,
nenhuma conta nova consegue entrar.

| Variável | Valor / nota |
|---|---|
| `EMAIL_PROVIDER` | `brevo` (ou `brevo-api`) ou `smtp`. **Nunca `console`** em produção: `console` só escreve no log e o email não é enviado. |
| `EMAIL_FROM` | remetente, p. ex. `KIXIMA <notificacoes@kixima.co.ao>`. O domínio tem de estar autorizado no provider (SPF/DKIM). |
| Com `brevo`: `BREVO_API_KEY` | chave da API (**segredo**) |
| Com `smtp`: `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD` | porta `465` = TLS directo; `587`/`2525` = STARTTLS. `SMTP_PASSWORD` é **segredo**. |

`APP_URL` (5.1) define o endereço dos links dos emails (confirmação de conta e recuperação de senha). Se ficar vazia
o link usa o cabeçalho `Host` do pedido, que pode ser forjado; define-a sempre.

### 5.3. Opcionais

| Variável | Para quê |
|---|---|
| `FLYWAY_ENABLED` | No JAR `7c5a3d9`: `true` só se usares o caminho 3B. Nos JAR com a correcção da secção 7b, `prod` já vem com `true` por omissão; `false` desliga (aplicação manual). |
| `CORS_ORIGINS` | origens web adicionais, separadas por vírgula. |
| `MFA_ENFORCE_FROM` | data a partir da qual o 2FA é obrigatório para administradores. **Atenção:** se já tiver passado, um administrador de empresa recém-registado tem de ligar o 2FA (em *Segurança*) antes de conseguir completar o cadastro. |
| `LOG_LEVEL`, `DB_POOL_MAX`, `DB_CONNECTION_TIMEOUT_MS` | ajustes de operação. |

---

## 6. Actualizar o JAR

O `Dockerfile` do repositório **constrói a imagem a partir do código-fonte**. Há duas formas de pôr esta versão a correr.

**Opção 1 (recomendada): construir a imagem a partir do commit entregue.**

```bash
git checkout 7c5a3d9
docker build -t kixima-backend-java:v2 .
# actualiza a imagem do serviço no stack para kixima-backend-java:v2 e faz o deploy
```

**Opção 2: usar o JAR entregue** (depois de verificar o SHA-256, ver secção 0), com uma imagem Java 21 ou superior:

```bash
docker run --rm -p 4001:4001 --env-file <ficheiro-de-ambiente> \
  -v "$PWD/kixima-backend-java-0.1.0-SNAPSHOT.jar:/app/app.jar:ro" \
  eclipse-temurin:21-jre java -jar /app/app.jar
```

(No Swarm, o equivalente é montar o JAR como volume só de leitura no serviço do backend.) O JAR é compilado para Java 21
e corre em Java 21 ou superior.

Depois de trocar, reinicia o serviço e acompanha os logs até ao arranque:

```bash
docker service logs -f <nome-do-serviço-backend>
```

Se aparecer `Schema-validation: missing column [...]` (por exemplo `[onboarding_draft]`), **a V2 não foi aplicada**
à base a que o serviço se ligou: volta ao passo 3 e confirma que estás na base certa.

---

## 7. Verificações finais

1. `GET /ready` responde `{"status":"ok", ...}`.
2. **Uma conta que já existia** (por exemplo, o Admin do Sistema) continua a entrar, **sem** pedir confirmação de email.
3. No painel do Admin do Sistema, abre **Configurações e Suporte → Prontidão para produção**: as verificações «Envio de
   email» e «Endereço público (APP_URL)» têm de estar em **OK** (nada em `console`).
4. Faz um registo de teste em `/cadastro` com uma caixa de correio **real**: o email chega, o link confirma a conta e o
   login leva a `/onboarding`.
5. Com essa conta, um endpoint de negócio (por exemplo `GET /api/notifications`) tem de responder **403
   `COMPANY_NOT_APPROVED`** até a empresa ser aprovada. Preenche o cadastro, submete, aprova em *Credenciamento* (due
   diligence) e confirma que o painel da empresa abre.
6. As empresas que estavam `PENDENTE` do cadastro antigo mantêm-se. O administrador delas passa a poder **entrar** para
   acompanhar o estado (antes era recusado), mas continua sem acesso ao negócio até à aprovação.
7. Apaga ou arquiva a conta de teste do passo 4 segundo o processo habitual da KIXIMA (não apagues diretamente na base
   sem copiar os dados).

---

## 7b. Resolução de problemas: `Schema-validation: missing column [onboarding_draft] in table [companies]`

**Significado.** O serviço ligou-se a uma base onde a V2 **não está aplicada**. O Hibernate (`ddl-auto: validate`, que
não se desactiva) recusa arrancar. A coluna `companies.onboarding_draft` só é criada pela V2
(`ADD COLUMN IF NOT EXISTS onboarding_draft text`) e a entidade `Company` mapeia-a; as duas estão coerentes.

**Porque acontece.** As migrações só correm se o Flyway estiver ligado, e nos JAR anteriores à correcção vinha
**desligado por omissão** em todos os perfis. O stack de produção também não o define, a menos que se acrescente
`FLYWAY_ENABLED` ao ambiente do serviço. Nos testes não havia nada que aplicasse a V2 à `kixima_test`. Resultado: quem não
aplicou a V2 à mão (guia, passo 3A) fica sem a coluna.

**Diagnóstico, só leitura** (na base a que o serviço se liga: confirma `DATABASE_URL_JDBC`):

```bash
docker exec -i <ID> psql -U <utilizador> -d kixima -Atc "
select current_database();
select 'coluna onboarding_draft: '||count(*) from information_schema.columns
  where table_schema='public' and table_name='companies' and column_name='onboarding_draft';
select 'histórico Flyway: '||coalesce(to_regclass('public.flyway_schema_history')::text,'não existe');"
docker exec -i <ID> psql -U <utilizador> -d kixima -Atc \
  "select version, type, success from flyway_schema_history order by installed_rank;"   # só se a tabela existir
```

| O que vês | Causa | O que fazer |
|---|---|---|
| coluna `0` e sem histórico Flyway | V2 nunca aplicada, Flyway desligado | Aplicar a V2 (3A) ou arrancar com Flyway ligado (3B) |
| coluna `0` e histórico só com `1 BASELINE` | Flyway ligado mas a V2 ainda não correu (ou o serviço não foi reiniciado com o JAR novo) | Reiniciar com o JAR que contém a V2; se persistir, aplicar a V2 (3A) |
| coluna `0` e histórico com `2` | outra base: o Flyway correu numa base diferente da que o serviço usa, ou a base foi restaurada de um backup anterior à V2 | Confirmar o nome da base em `DATABASE_URL_JDBC`; aplicar a V2 à base certa (3A) |
| coluna `1` e o erro persiste | o serviço usa outra base ou outro servidor | Confirmar `DATABASE_URL_JDBC` (host `db_kixima`, base `kixima`) |

**Correcção no código** (a que acompanha este guia): o Flyway passa a vir **ligado por omissão** no perfil `prod` e no
perfil `test` (`application-prod.yml` e `application-test.yml`, este em `src/test/resources`, fora do JAR). A validação do Hibernate mantém-se. Efeitos:

- `mvn test` aplica sozinho à `kixima_test` as migrações em falta (a V2); já não é preciso `psql -f`. O utilizador da base
  de testes tem de ser dono das tabelas.
- Em `prod`, o serviço aplica as migrações ao arrancar. Numa base que já tem o schema e não tem histórico, o Flyway marca-a
  como V1 e só corre a V2 (idempotente, não apaga dados). **Faz backup antes de pôr o JAR novo** (secção 2).
- `FLYWAY_ENABLED=false` volta a desligar, para quem prefira aplicar as migrações à mão (3A).

---

## 8. Plano de recuo (só da aplicação)

- **Não reverter o schema.** A V2 só acrescenta e relaxa restrições. Voltar a pôr `NOT NULL` em `name`/`tax_id` falha se
  já houver rascunhos, e um valor de enum não se retira de forma segura. Para recuar, recua-se **só a aplicação**: volta
  à imagem/JAR anterior e reinicia o serviço. O JAR antigo ignora as colunas novas e funciona com o schema já migrado.
- **Antes de recuar**, vê se já há contas criadas pelo novo fluxo:

```sql
select count(*) from companies where status::text = 'RASCUNHO';   -- o JAR antigo não conhece este estado
select count(*) from users where email_verified_at is null;       -- o JAR antigo deixaria estas contas entrar sem confirmar
```

  Se os dois forem `0`, o recuo é seguro. Se não, essas contas são registos novos: mantém o novo JAR, ou decide caso a
  caso o que fazer com elas. Não as apagues sem copiar os dados primeiro.
- **Restaurar o backup** (`pg_restore`) é o último recurso: com o serviço **parado**, e sabendo que perde tudo o que foi
  escrito desde o backup.

---

## 9. ⚠️ Não executar `mvn test` com `DATABASE_URL_JDBC` apontada à produção

Os testes da aplicação usam a base `kixima_test` em `localhost` (perfil `test`), mas o `FlywayBaselineTest` lê a variável
de ambiente **`DATABASE_URL_JDBC`** para criar (e apagar) bases temporárias. Se essa variável estiver definida com o
endereço de produção (`db_kixima`) na shell onde corres `mvn test`, esse teste cria e apaga bases temporárias **no
servidor de produção**.

- Corre `mvn test` com `DATABASE_URL_JDBC` **por definir** (`unset DATABASE_URL_JDBC`).
- Nunca corras a suite de testes em máquinas ou contentores que tenham as variáveis de produção no ambiente.
- A base de testes tem de ter a V2 aplicada: `psql -d kixima_test -f src/main/resources/db/migration/V2__cadastro_progressivo.sql`.

---

## 10. Estado da validação antes da entrega

| Verificação | Resultado |
|---|---|
| Testes automatizados (`mvn test`), contra uma base `kixima_test` limpa, só com os dados de exemplo | **305 testes, 0 falhas** |
| Arranque do JAR em perfil `prod`, base com schema antigo + `FLYWAY_ENABLED=true` | pronto; histórico Flyway `1 BASELINE`, `2 SQL`; registo de conta devolve 201 |
| Arranque do JAR em perfil `prod`, V2 aplicada à mão, Flyway desligado | pronto |
| *Correcção 7b:* `prod`, base só com a V1, `FLYWAY_ENABLED` por definir | pronto; histórico `1 BASELINE`, `2 SQL` |
| *Correcção 7b:* `prod`, base só com a V1, `FLYWAY_ENABLED=false` | recusa arrancar com `missing column [onboarding_draft]` (esperado) |
| *Correcção 7b:* `prod`, V2 já aplicada à mão e sem histórico, `FLYWAY_ENABLED` por definir | pronto; histórico `1 BASELINE`, `2 SQL` |
| *Correcção 7b:* `mvn test` contra uma base com os dados de exemplo e **sem** a V2 | **305 testes, 0 falhas**; o Flyway aplica a V2 |
| Arranque do JAR em perfil `prod`, **sem** V2 | recusa arrancar com `Schema-validation: missing column [onboarding_draft]` (esperado) |
| Teste E2E do percurso completo (registo → email → cadastro por etapas → submissão → aprovação), num browser real | 25 passos, sem erros |
| Testes E2E anteriores (percursos, acessibilidade, mobile) | 70/70 |

Estas validações foram feitas em bases locais descartáveis. **Nada foi executado contra a base de produção.** O envio
real de email (SMTP/Brevo) **não** foi testado: usa-se o passo 7.4 para o confirmar em produção.
