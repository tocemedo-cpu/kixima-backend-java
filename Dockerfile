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
RUN mvn -B -q -o package -DskipTests

# --- runtime ---
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

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
