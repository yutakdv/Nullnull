# apps/web image. Build context is the repository root for the npm workspace.
# Stages required by scripts/verify_target_stack.py: test, runtime, e2e, tooling.

# node:24.20.0-bookworm-slim
FROM node@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e AS deps
WORKDIR /workspace
COPY package.json package-lock.json ./
COPY apps/web/package.json apps/web/
COPY packages/api-client/package.json packages/api-client/
RUN npm ci --no-audit --no-fund

FROM deps AS source
COPY . .

FROM source AS test
WORKDIR /workspace/apps/web
CMD ["npm", "run", "verify:ci"]

FROM source AS tooling
CMD ["npm", "run", "api:check"]

FROM source AS build
WORKDIR /workspace/apps/web
RUN npm run build

# Serves dist with no npm deps at all.
FROM node@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e AS runtime
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /workspace/apps/web/dist ./dist
COPY apps/web/serve.mjs ./serve.mjs
EXPOSE 4173
USER node
CMD ["node", "serve.mjs"]

# mcr.microsoft.com/playwright:v1.56.0-noble
FROM mcr.microsoft.com/playwright@sha256:35246d87a7c88ea9b771c65d33171b2611b02a8253b4b12ce6f94376c55f99f2 AS e2e
COPY --from=source /workspace /workspace
WORKDIR /workspace/apps/web
CMD ["npm", "run", "test:e2e:integration"]
