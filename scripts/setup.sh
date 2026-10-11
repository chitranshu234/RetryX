#!/usr/bin/env bash
# ============================================================================
#  Retryx — one-time setup
# ============================================================================
#  Installs dashboard dependencies and compiles both Java apps, checking the
#  prerequisites first so a missing tool is reported by name rather than as a
#  wall of build output.
#
#      bash scripts/setup.sh
#
#  Then:  docker compose up --build        (everything)
#    or:  make db && make api && make dashboard   (local development)
# ============================================================================

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; }

missing=0

bold "Checking prerequisites"

if command -v node >/dev/null 2>&1; then
  node_major=$(node -v | sed 's/^v//' | cut -d. -f1)
  if [ "$node_major" -ge 20 ]; then
    ok "node $(node -v)"
  else
    fail "node $(node -v) — Vite 6 needs Node 20 or newer"
    missing=1
  fi
else
  fail "node is not installed — https://nodejs.org (20 LTS or newer)"
  missing=1
fi

if command -v npm >/dev/null 2>&1; then
  ok "npm $(npm -v)"
else
  fail "npm is not installed (it ships with Node)"
  missing=1
fi

if command -v java >/dev/null 2>&1; then
  java_major=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
  if [ "${java_major:-0}" -ge 21 ]; then
    ok "java $java_major"
  else
    fail "java $java_major — this project targets Java 21"
    missing=1
  fi
else
  fail "java is not installed — https://adoptium.net (Temurin 21)"
  missing=1
fi

if command -v mvn >/dev/null 2>&1; then
  ok "maven $(mvn -v 2>/dev/null | head -1 | awk '{print $3}')"
else
  fail "maven is not installed — https://maven.apache.org/download.cgi"
  missing=1
fi

if command -v docker >/dev/null 2>&1; then
  if docker info >/dev/null 2>&1; then
    ok "docker is running"
  else
    warn "docker is installed but not running — needed for Postgres and the integration tests"
  fi
else
  warn "docker is not installed — needed for Postgres and the integration tests"
fi

if [ "$missing" -ne 0 ]; then
  echo
  fail "Install the tools marked above, then run this again."
  exit 1
fi

echo
bold "Installing dashboard dependencies"
cd "$ROOT/frontend"
# `npm install` rather than `npm ci`: there is no lockfile on a fresh clone, and
# this run is what creates it. Commit the lockfile afterwards so the Docker
# build can use `npm ci` and be reproducible.
npm install
ok "node_modules ready"

echo
bold "Type-checking the dashboard"
npm run typecheck
ok "no type errors"

echo
bold "Compiling the backend"
cd "$ROOT/backend"
# Tests are skipped here because the integration tests need Docker. Run them
# separately with `make test`.
mvn -B -q clean package -DskipTests
ok "backend/target/retryx-api-1.0.0.jar"

echo
bold "Compiling the receiver"
cd "$ROOT/receiver"
mvn -B -q clean package -DskipTests
ok "receiver/target/retryx-receiver-1.0.0.jar"

echo
bold "Setup complete"
cat <<'EOF'

  Run everything in Docker:

      docker compose up --build

  Or run the pieces locally, in four terminals:

      make db           Postgres on :5432
      make receiver     reference receiver on :9090
      make api          backend on :8080
      make dashboard    dashboard on :5173

  Then open http://localhost:5173 and click "Use the demo key".

  Tests:

      make test-unit    JUnit, no Docker needed
      make test-it      Testcontainers, needs Docker
      make load         k6 load test with 20% injected failures

EOF
