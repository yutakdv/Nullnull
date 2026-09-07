# apps/web image. Build context is the repository root so the npm workspace
# lockfile and packages/api-client are available.
#
# Stages required by scripts/verify_target_stack.py: test, runtime, e2e, tooling.
# External FROM references are digest-pinned; see docs/engineering/LOCAL_DEVELOPMENT.md.

# node:24.20.0-bookworm-slim
FROM node@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e AS deps
WORKDIR /workspace
COPY package.json package-lock.json ./
COPY apps/web/package.json apps/web/package.json
COPY packages/api-client/package.json packages/api-client/package.json
RUN npm ci --no-audit --no-fund

FROM deps AS source
COPY . .

# Lint, format, typecheck, unit tests and production build.
FROM source AS test
WORKDIR /workspace/apps/web
CMD ["npm", "run", "verify:ci"]

# Regenerates the API client and fails if the committed output drifts.
FROM source AS tooling
WORKDIR /workspace
CMD ["npm", "run", "api:check"]

FROM source AS build
WORKDIR /workspace/apps/web
RUN npm run build

# Serves the built bundle for integration journeys.
FROM node@sha256:ba849c60be29959425b8734d57b8b4b7d56f98edd9504c9af091d5281095a71e AS runtime
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /workspace/apps/web/dist ./dist
COPY --from=build /workspace/node_modules ./node_modules
COPY --from=build /workspace/apps/web/package.json ./package.json
EXPOSE 4173
USER node
CMD ["npm", "run", "preview"]

# mcr.microsoft.com/playwright:v1.56.0-noble
FROM mcr.microsoft.com/playwright@sha256:35246d87a7c88ea9b771c65d33171b2611b02a8253b4b12ce6f94376c55f99f2 AS e2e
WORKDIR /workspace
COPY package.json package-lock.json ./
COPY apps/web/package.json apps/web/package.json
COPY packages/api-client/package.json packages/api-client/package.json
RUN npm ci --no-audit --no-fund
COPY . .
WORKDIR /workspace/apps/web
CMD ["npm", "run", "test:e2e:integration"]
