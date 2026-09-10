.PHONY: help bootstrap lint test test-python test-android backend-up backend-down secret-scan

PYTHON ?= python
VENV_DIR ?= .venv
ifeq ($(OS),Windows_NT)
    VENV_BIN = $(VENV_DIR)/Scripts
else
    VENV_BIN = $(VENV_DIR)/bin
endif

help:
	@echo "Safe Cross KR Mono-repo commands:"
	@echo "  make bootstrap    - Create virtual environment and install dependencies"
	@echo "  make lint         - Run linters on Python code"
	@echo "  make test         - Run all unit tests (backend, data-pipeline)"
	@echo "  make backend-up   - Start PostgreSQL+PostGIS container"
	@echo "  make backend-down - Stop PostgreSQL+PostGIS container"
	@echo "  make secret-scan  - Scan for leaked secrets and forbidden safety phrases"

bootstrap:
	$(PYTHON) -m venv $(VENV_DIR)
	$(VENV_BIN)/python -m pip install --upgrade pip
	$(VENV_BIN)/python -m pip install fastapi uvicorn pydantic pydantic-settings pytest httpx ruff
	$(VENV_BIN)/python -m pip install -e backend -e data-pipeline
	@echo "Bootstrap completed successfully."

lint:
	$(VENV_BIN)/ruff check backend data-pipeline

test: test-python

test-python:
	$(VENV_BIN)/pytest backend/tests data-pipeline/tests -v

test-android:
	@echo "Running Android unit tests..."
	cd android-app && ./gradlew test

backend-up:
	docker compose -f infra/docker-compose.yml up -d

backend-down:
	docker compose -f infra/docker-compose.yml down

secret-scan:
	@echo "Scanning for accidental secrets or forbidden safety phrases..."
	@git grep -i -E "(safe|100%|건너세요|안전합니다|차가 없습니다)" -- :!docs/ :!SKILL.md :!PRD.md :!SRD.md :!TRD.md :!DATA_SOURCES.md :!VIBE_CODING_PROMPTS.md :!IMPLEMENTATION_PLAN.md :!Makefile :!run.ps1 || true
