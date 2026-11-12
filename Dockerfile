# kixima-backend-java — imagem standalone, sem qualquer dependência do Render
# ou de outro serviço gerido. Corre em qualquer host de contentores (Docker,
# Podman, Kubernetes, Fly.io, ECS, Cloud Run, VM própria, ...).
#
# Build:
#   docker build -t kixima-backend-java .
#
# Correr (produção — exige as variáveis abaixo; ver README.md e
# docs/provisionamento-postgresql.md):
#   docker run -p 4001:4001 \
#     -e SPRING_PROFILES_ACTIVE=prod \
#     -e DATABASE_URL_JDBC=jdbc:postgresql://<host>:5432/kixima_java \
#     -e DATABASE_USER=kixima_java \
#     -e DATABASE_PASSWORD=... \
#     -e JWT_SECRET=... \
#     -e APP_URL=https://... \
#     kixima-backend-java

# --- build ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Resolve as dependências numa camada própria — só volta a descarregar se o
# pom.xml mudar, não a cada alteração de código.
RUN mvn -B -q dependency:go-offline
COPY src src
# Sem "-o" (offline) aqui de propósito: dependency:go-offline não garante
# resolver 100% do grafo (ex.: algumas dependências de âmbito "test" só
# usadas por certos plugins) — validado a ir a baixo com "-o" mesmo com
# rede disponível. A cache da camada anterior já evita a maior parte do
# tráfego repetido; esta camada só reconfirma o que falta.
RUN mvn -B -q package -DskipTests

# --- runtime ---
# Alpine fixado (3.24): a etiqueta `21-jre-alpine` flutua com cada nova versão do Alpine, e o nome do pacote do cliente PostgreSQL
# (abaixo) depende da versão do Alpine. Ao subir de versão, confirme que `postgresql17-client` (ou o major da base) existe nela.
FROM eclipse-temurin:21-jre-alpine-3.24 AS runtime
WORKDIR /app

# Cliente PostgreSQL 17 (pg_dump/pg_restore/psql) para a cópia de segurança da própria aplicação (BackupService corre `pg_dump`
# pelo PATH). Tem de ser o MESMO major do servidor da stack (postgres:17.11): o pg_dump recusa-se a copiar um servidor mais
# recente do que ele. Só o subpacote `-client` (não o servidor, nem o `postgresql-client` genérico, que instalaria outro major).
# O `pg_dump` fica em /usr/bin por um trigger do pacote postgresql-common. A verificação falha o BUILD se o major não for o 17;
# a versão exacta instalada fica escrita no log do build. Sem credenciais: nada de ARG/ENV de senhas aqui.
RUN apk add --no-cache postgresql17-client \
    && pg_dump --version \
    && pg_dump --version | grep -q 'pg_dump (PostgreSQL) 17\.'

# Utilizador não-root — o processo nunca precisa de privilégios de root.
RUN addgroup -S kixima && adduser -S kixima -G kixima
USER kixima

COPY --from=build /build/target/kixima-backend-java-*.jar app.jar

# Só documental — a porta real vem sempre de $PORT (application.yml:
# server.port: ${PORT:4001}), como em qualquer host de contentores.
EXPOSE 4001

# Sem SPRING_PROFILES_ACTIVE por omissão, de propósito: arrancar sem "prod"
# explícito cai nos valores de desenvolvimento (ver application.yml) em vez
# de arriscar produção com um perfil errado — o mesmo desenho fail-closed do
# CORS/cookie/ProducaoStartupGuard (ver Fase 1 da auditoria de segurança).
ENTRYPOINT ["java", "-jar", "app.jar"]
