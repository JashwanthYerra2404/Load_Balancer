.PHONY: build run backend backend2 backend3 test test-verbose clean help

# ============================================================
# Build
# ============================================================

## build: Compile and package the load balancer fat JAR
build:
	@echo "==> Building load balancer and backend simulator JAR..."
	mvn clean package -DskipTests
	@echo "==> Done. Fat JAR located at target/load-balancer-1.0.0-SNAPSHOT.jar"

## clean: Remove build artifacts
clean:
	@echo "==> Cleaning build artifacts..."
	mvn clean

# ============================================================
# Run
# ============================================================

## run: Start the load balancer with default config
run:
	@echo "==> Starting load balancer..."
	mvn exec:java -Dexec.mainClass="com.loadbalancer.LoadBalancerApplication" -Dexec.args="--config configs/config.yaml"

## backend: Start a backend simulator on port 9001
backend:
	@echo "==> Starting backend simulator backend-1 on port 9001..."
	mvn exec:java -Dexec.mainClass="com.loadbalancer.BackendSimulator" -Dexec.args="--port 9001 --name backend-1"

## backend2: Start a second backend simulator on port 9002
backend2:
	@echo "==> Starting backend simulator backend-2 on port 9002..."
	mvn exec:java -Dexec.mainClass="com.loadbalancer.BackendSimulator" -Dexec.args="--port 9002 --name backend-2"

## backend3: Start a third backend simulator on port 9003
backend3:
	@echo "==> Starting backend simulator backend-3 on port 9003..."
	mvn exec:java -Dexec.mainClass="com.loadbalancer.BackendSimulator" -Dexec.args="--port 9003 --name backend-3"

# ============================================================
# Testing
# ============================================================

## test: Run all unit tests
test:
	@echo "==> Running unit tests..."
	mvn test

## test-verbose: Run all tests with console output
test-verbose:
	@echo "==> Running unit tests (verbose)..."
	mvn test -Dsurefire.useFile=false

# ============================================================
# Help
# ============================================================

## help: Print this help message
help:
	@echo "Usage: make <target>"
	@echo ""
	@echo "Targets:"
	@grep -E '^## ' Makefile | sed 's/^## /  /'
