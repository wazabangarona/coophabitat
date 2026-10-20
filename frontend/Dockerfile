# syntax=docker/dockerfile:1
# Image web de CoopHabitat : compilation Angular puis service statique par nginx (utilisateur non privilégié).
# Le contexte de construction est la racine du dépôt (le fichier de configuration nginx est dans deploy/).
ARG NODE_IMAGE=node:24-alpine
ARG NGINX_IMAGE=nginxinc/nginx-unprivileged:1.28-alpine

FROM ${NODE_IMAGE} AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npx ng build --configuration production

FROM ${NGINX_IMAGE}
COPY deploy/nginx/default.conf /etc/nginx/templates/default.conf.template
# Adresse du serveur Spring Boot : le service « backend » de Docker Compose par défaut, surchargée sur Render.
ENV COOPHABITAT_API_HOSTPORT=backend:8080 \
    NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    NGINX_ENVSUBST_FILTER="^(COOPHABITAT_|NGINX_LOCAL_RESOLVERS)"
COPY deploy/nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /src/dist/frontend/browser /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
