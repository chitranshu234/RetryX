# ============================================================================
#  Retryx — common tasks
# ============================================================================
#  `make help` lists everything.
# ============================================================================

.DEFAULT_GOAL := help
.PHONY: help up down logs reset db api worker receiver dashboard \
        test test-unit test-it typecheck build load clean event

help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ---- docker ---------------------------------------------------------------

up: ## Start everything (postgres, api, second worker, receiver, dashboard)
	docker compose up --build -d
	@echo ""
	@echo "  Dashboard  http://localhost:5173"
	@echo "  API        http://localhost:8080/api"
	@echo "  Receiver   http://localhost:9090/stats"
	@echo ""
	@echo "  Demo key:  rtx_sk_demo_0000000000000000000000"
	@echo ""

down: ## Stop everything, keeping the database volume
	docker compose down

reset: ## Stop everything and delete the database volume
	docker compose down -v

logs: ## Follow the api and worker logs
	docker compose logs -f api worker

# ---- local development ----------------------------------------------------

db: ## Start only Postgres, for running the apps from an IDE
	docker compose up -d postgres

api: ## Run the backend locally (needs `make db`)
	cd backend && mvn spring-boot:run

worker: ## Run a second worker locally, to demonstrate SKIP LOCKED
	cd backend && RETRYX_PORT=8081 RETRYX_WORKER_ID=local-worker-2 \
		RETRYX_DEMO_ENABLED=false mvn spring-boot:run

receiver: ## Run the reference receiver locally
	cd receiver && mvn spring-boot:run

dashboard: ## Run the dashboard dev server
	cd frontend && npm install && npm run dev

# ---- tests ----------------------------------------------------------------

test: test-unit test-it ## Run every test

test-unit: ## Unit tests only — no Docker needed
	cd backend && mvn -B test

test-it: ## Integration tests — needs Docker for Testcontainers
	cd backend && mvn -B verify

typecheck: ## Type-check the dashboard
	cd frontend && npm install && npm run typecheck

# ---- load test ------------------------------------------------------------

load: ## Run the k6 load test with 20% injected failures
	@echo "Restarting the receiver with RECEIVER_FAILURE_RATE=0.2…"
	RECEIVER_FAILURE_RATE=0.2 docker compose up -d --force-recreate receiver
	curl -fsS -X POST http://localhost:9090/reset > /dev/null
	k6 run load/k6-delivery.js

# ---- misc -----------------------------------------------------------------

event: ## Send one test event through the API
	curl -sS -X POST http://localhost:8080/api/events \
		-H "X-Api-Key: rtx_sk_demo_0000000000000000000000" \
		-H "Content-Type: application/json" \
		-H "Idempotency-Key: make-$$(date +%s)" \
		-d '{"eventType":"order.created","payload":{"orderId":"A-1","total":42.5}}'

build: ## Build both jars and the dashboard bundle
	cd backend && mvn -B -q clean package -DskipTests
	cd receiver && mvn -B -q clean package -DskipTests
	cd frontend && npm install && npm run build

clean: ## Remove build output
	cd backend && mvn -q clean
	cd receiver && mvn -q clean
	rm -rf frontend/dist frontend/node_modules
