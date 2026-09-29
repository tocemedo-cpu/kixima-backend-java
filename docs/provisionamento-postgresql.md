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

## Resumo rápido (checklist)

- [ ] `CREATE ROLE kixima_java WITH LOGIN PASSWORD '...'`
- [ ] `CREATE DATABASE kixima_java OWNER kixima_java`
- [ ] (só se necessário) `CREATE EXTENSION pg_trgm` como superuser
- [ ] `DATABASE_URL_JDBC` / `DATABASE_USER` / `DATABASE_PASSWORD` definidas
- [ ] `FLYWAY_ENABLED=true` definida
- [ ] Primeiro arranque da aplicação → confirmar `flyway_schema_history` e `/ready`
