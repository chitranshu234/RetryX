# ============================================================================
#  Retryx dashboard — build with Node, serve with nginx
# ============================================================================

FROM node:22-alpine AS build
WORKDIR /build

# npm ci rather than npm install: it installs exactly what the lockfile says,
# so the image is reproducible and a transitive release cannot change the build.
# Falls back to install when no lockfile is committed yet.
COPY package.json package-lock.json* ./
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi

COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
