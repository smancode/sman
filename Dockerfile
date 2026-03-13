# Build stage for frontend
FROM node:22-alpine AS frontend-builder

WORKDIR /app

# Install pnpm
RUN corepack enable && corepack prepare pnpm@latest --activate

# Copy package files
COPY package.json pnpm-lock.yaml ./

# Install dependencies
RUN pnpm install --frozen-lockfile

# Copy source
COPY . .

# Build frontend and bundle OpenClaw
RUN pnpm build

# Production stage
FROM node:22-alpine

WORKDIR /app

# Install pnpm for runtime
RUN corepack enable && corepack prepare pnpm@latest --activate

# Copy built assets
COPY --from=frontend-builder /app/dist ./dist
COPY --from=frontend-builder /app/bundled ./bundled
COPY --from=frontend-builder /app/node_modules ./node_modules
COPY --from=frontend-builder /app/package.json ./

# Create data directory for persistence
RUN mkdir -p /app/data

# Environment defaults
ENV PORT=3000
ENV GATEWAY_PORT=18789
ENV GATEWAY_TOKEN=sman-change-me-in-production
ENV NODE_ENV=production

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:3000/api/health || exit 1

EXPOSE 3000

# Start the server
CMD ["node", "dist/server/index.js"]
