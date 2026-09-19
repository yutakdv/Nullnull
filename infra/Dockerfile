# Dedicated quality image: no dependency or change to the FE build stage.
FROM node@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e
WORKDIR /workspace/infra
COPY infra/package.json infra/package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY infra/ ./
COPY scripts/infra-check.mjs /workspace/scripts/infra-check.mjs
CMD ["node", "/workspace/scripts/infra-check.mjs"]
