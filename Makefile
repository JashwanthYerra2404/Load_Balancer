.PHONY: build run test test-verbose test-race bench clean backend lint fmt

# ============================================================
# Build
# ============================================================

## build: Compile the load balancer and backend binaries
build:
	@echo "==> Building load balancer..."
	go build -o bin/loadbalancer ./cmd/loadbalancer
	@echo "==> Building backend simulator..."
	go build -o bin/backend ./cmd/backend
	@echo "==> Done."

## clean: Remove build artifacts
clean:
	rm -rf bin/

# ============================================================
# Run
# ============================================================

## run: Start the load balancer with default config
run: build
	./bin/loadbalancer --config configs/config.yaml

## backend: Start a backend simulator on port 9001
backend: build
	./bin/backend --port 9001 --name backend-1

## backend2: Start a second backend simulator on port 9002
backend2: build
	./bin/backend --port 9002 --name backend-2

# ============================================================
# Testing
# ============================================================

## test: Run all unit tests
test:
	go test ./... -count=1

## test-verbose: Run all tests with verbose output
test-verbose:
	go test ./... -v -count=1

## test-race: Run all tests with race detector enabled
test-race:
	go test ./... -v -race -count=1

## bench: Run all benchmarks
bench:
	go test ./... -bench=. -benchmem -run=^$$ -count=3

## cover: Run tests with coverage report
cover:
	go test ./... -coverprofile=coverage.out -count=1
	go tool cover -html=coverage.out -o coverage.html
	@echo "==> Coverage report: coverage.html"

# ============================================================
# Code Quality
# ============================================================

## fmt: Format all Go code
fmt:
	gofmt -s -w .

## vet: Run go vet
vet:
	go vet ./...

## lint: Run all code quality checks
lint: fmt vet
	@echo "==> Code quality checks passed."

# ============================================================
# Help
# ============================================================

## help: Print this help message
help:
	@echo "Usage: make <target>"
	@echo ""
	@echo "Targets:"
	@grep -E '^## ' Makefile | sed 's/^## /  /'
