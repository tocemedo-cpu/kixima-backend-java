# Provisionar a PostgreSQL do `kixima-backend-java`

Procedimento para criares, no **teu** servidor PostgreSQL, uma base de dados
nova e independente para este projecto — sem tocar em nada do Node.

**Isto é um guia — ninguém correu nada disto ainda.** Os nomes abaixo
(`kixima_java`, a password) são sugestões; ajusta-os ao que preferires,
desde que uses os mesmos valores depois nas variáveis de ambiente.

---

## 0. Antes de começar

- Precisas de acesso de **superuser** (ou de um utilizador com `CREATEDB`
  e `CREATEROLE`) ao teu servidor PostgreSQL — normalmente o utilizador
  `postgres`.
- **PostgreSQL 13 ou mais recente** (a extensão `pg_trgm` que o schema usa é
  "trusted" a partir da 13, o que significa que o dono da base a consegue
  instalar sozinho, sem precisar de ser superuser — ver o passo 3).
- Esta base **não substitui nem se liga à do Node** — são duas bases
  completamente separadas, mesmo que vivam no mesmo servidor físico.

---

## 1. Criar o utilizador (role) dedicado ao Java

Um utilizador só para este projecto, nunca o mesmo que o Node usa — isolamento
a sério, não só de base de dados mas de credenciais.

### Via `psql`

```sql
CREATE ROLE kixima_java WITH LOGIN PASSWORD 'ESCOLHE_UMA_PASSWORD_FORTE_AQUI';
```

### Via pgAdmin

1. **Login/Group Roles** → botão direito → **Create → Login/Group Role...**
2. Aba **General**: Name = `kixima_java`
3. Aba **Definition**: Password = a tua password forte
4. Aba **Privileges**: **Can login?** = Yes (todo o resto pode ficar `No` —
   este utilizador não precisa de `Superuser`, `Create role` nem
   `Create DB`, só de ser dono da SUA base, criada no passo seguinte)
5. **Save**

---

## 2. Criar a base de dados, com o `kixima_java` como dono

### Via `psql`

```sql
CREATE DATABASE kixima_java OWNER kixima_java;
```

### Via pgAdmin

1. **Databases** → botão direito → **Create → Database...**
2. **Database**: `kixima_java`
3. **Owner**: `kixima_java` (o role criado no passo 1)
4. **Save**

Ser **dono** da base (não apenas ter uma permissão solta) é o que dá ao
`kixima_java` tudo o que o Flyway precisa para aplicar o `V1__baseline.sql`
sozinho: criar tabelas, tipos, índices, funções, triggers, chaves
estrangeiras — sem precisar de superuser.

---

## 3. A extensão `pg_trgm`

Com PostgreSQL 13+, `pg_trgm` é uma extensão **trusted**: o próprio dono da
base (`kixima_java`) já a consegue instalar sozinho — o `CREATE EXTENSION
IF NOT EXISTS pg_trgm` que já está dentro do `V1__baseline.sql` normalmente
**não precisa de nenhum passo manual aqui.**

**Só se isso falhar** (algumas instalações restringem `CREATE EXTENSION`
mesmo a extensões trusted, por política), um superuser corre isto **uma
vez**, antes do Flyway:

```sql
\c kixima_java
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

(pgAdmin: liga-te à base `kixima_java`, abre uma **Query Tool** nela, e
corre o mesmo `CREATE EXTENSION`.)

---

## 4. Confirmar a ligação (opcional, mas recomendado)

```bash
psql "postgresql://kixima_java:ESCOLHE_UMA_PASSWORD_FORTE_AQUI@<HOST>:<PORTA>/kixima_java" -c "SELECT current_user, current_database();"
```

Deve devolver `kixima_java | kixima_java`. Se falhar por SSL, ver a nota de
`sslmode` no passo 5.

---

## 5. Variáveis de ambiente para o `kixima-backend-java`

Estas três variáveis substituem os valores de omissão em `application.yml`
(que apontam para `localhost:5432/kixima_test`, só para desenvolvimento):

| Variável | Valor |
|---|---|
| `DATABASE_URL_JDBC` | `jdbc:postgresql://<HOST>:<PORTA>/kixima_java` (acrescenta `?sslmode=require` no fim se o servidor exigir SSL — a generalidade dos serviços geridos exige) |
| `DATABASE_USER` | `kixima_java` |
| `DATABASE_PASSWORD` | a password escolhida no passo 1 |

Exemplo completo (servidor local, sem SSL):
```
DATABASE_URL_JDBC=jdbc:postgresql://127.0.0.1:5432/kixima_java
DATABASE_USER=kixima_java
DATABASE_PASSWORD=ESCOLHE_UMA_PASSWORD_FORTE_AQUI
```

Exemplo com SSL (servidor remoto/gerido):
```
DATABASE_URL_JDBC=jdbc:postgresql://db.exemplo.com:5432/kixima_java?sslmode=require
DATABASE_USER=kixima_java
DATABASE_PASSWORD=ESCOLHE_UMA_PASSWORD_FORTE_AQUI
```

Onde definir isto depende de como vais correr a aplicação (ainda não
decidido — fica para a fase de deploy):
- localmente: `export DATABASE_URL_JDBC=... DATABASE_USER=... DATABASE_PASSWORD=...` antes de `mvn spring-boot:run`;
- num serviço gerido (Render, Railway, etc.): painel de "Environment Variables" do serviço;
- num contentor: `docker run -e DATABASE_URL_JDBC=... -e DATABASE_USER=... -e DATABASE_PASSWORD=...`.

---

## 6. Ligar o Flyway

Uma variável a mais, ao lado das três de cima:

```
FLYWAY_ENABLED=true
```

**Sem esta variável, nada do que preparámos corre** — o Flyway fica
desligado por omissão (`application.yml`) precisamente para nunca correr
sem intenção explícita.

Com `FLYWAY_ENABLED=true` e as três variáveis do passo 5 a apontar para a
base `kixima_java` vazia, o `V1__baseline.sql` corre **automaticamente** no
arranque seguinte da aplicação — não é preciso nenhum comando manual de
Flyway.

---

## 7. Confirmar que correu bem

Depois do primeiro arranque com estas 4 variáveis, dois sinais confirmam
sucesso:

1. **No log de arranque**, uma linha como:
   ```
   Successfully applied 1 migration to schema "public", now at version v1
   ```
   seguida de `Started KiximaApplication` (sem nenhum `ERROR` nem
   `SchemaManagementException` a seguir — isso indicaria uma diferença
   entre o schema aplicado e o que as entidades JPA esperam).

2. **Na base de dados**, via `psql` ou pgAdmin:
   ```sql
   SELECT version, description, success FROM flyway_schema_history;
   -- deve devolver: 1 | baseline | t
   ```

3. Se a aplicação estiver a responder em HTTP:
   ```bash
   curl http://<host>:<porta>/ready
   -- {"status":"ok","database":"ok",...}
   ```

---

## 8. Onde o PostgreSQL guarda os dados (e a pasta dentro de pasta)

**Regra:** com `postgres:16` ou `postgres:17`, o volume monta-se em `/var/lib/postgresql/data`, nem mais acima nem mais abaixo.

Essas imagens declaram `/var/lib/postgresql/data` como `VOLUME`. Se montar um nível acima
(`./data:/var/lib/postgresql`), o Docker cria por baixo um **volume anónimo** nesse caminho e é aí que a base vai
parar. Na pasta do host só aparece uma pasta `data` vazia (pasta dentro de pasta). Num serviço Swarm, cada tarefa nova
recebe um volume anónimo novo, por isso a base aparece **vazia** depois de cada actualização ou reinício e o backend
falha com `Schema-validation: missing table [addon_cobrancas]`.

(A imagem `postgres:18` mudou a convenção: a pasta de dados é `/var/lib/postgresql/18/docker` e o volume monta-se em
`/var/lib/postgresql`. Use o ponto de montagem que corresponde à versão da imagem.)

### Confirmar onde estão os dados agora

```bash
docker exec -it <contentor-da-base> psql -U <utilizador> -d <base> -c "SHOW data_directory;" -c "\dt"
docker inspect <contentor-da-base> --format '{{json .Mounts}}'
```

Se o `Mounts` mostrar um volume **sem nome** com `Destination: /var/lib/postgresql/data` ao lado do seu bind
(`/var/lib/postgresql`), é este o caso: os dados estão no volume anónimo.

### Corrigir sem perder dados

1. **Copie primeiro, antes de mexer em nada:**
   `docker exec <contentor-da-base> pg_dumpall -U <utilizador> > copia.sql`
2. Corrija o volume para `/var/lib/postgresql/data` (ver `docker-stack.swarm.yml`) e volte a fazer o deploy.
3. Se a base ficou vazia, restaure: `docker exec -i <contentor-da-base> psql -U <utilizador> -f - < copia.sql`.
   Se não chegou a haver cópia, os dados antigos podem ainda estar num volume anónimo:
   `docker volume ls -f dangling=true`, e `docker run --rm -v <volume>:/v alpine ls /v` (procure `PG_VERSION`).
4. Só depois ligue o Flyway se a base for mesmo nova (secção 6).

A palavra-passe da base nunca deve estar escrita no ficheiro da stack: passe-a por variável de ambiente
(`DATABASE_PASSWORD`) ou por Docker secret, e mude a que já esteve em texto.

---

## Resumo rápido (checklist)

- [ ] `CREATE ROLE kixima_java WITH LOGIN PASSWORD '...'`
- [ ] `CREATE DATABASE kixima_java OWNER kixima_java`
- [ ] (só se necessário) `CREATE EXTENSION pg_trgm` como superuser
- [ ] `DATABASE_URL_JDBC` / `DATABASE_USER` / `DATABASE_PASSWORD` definidas
- [ ] `FLYWAY_ENABLED=true` definida
- [ ] Primeiro arranque da aplicação → confirmar `flyway_schema_history` e `/ready`
- [ ] Volume do Postgres montado em `/var/lib/postgresql/data` (imagens 16/17) — secção 8
