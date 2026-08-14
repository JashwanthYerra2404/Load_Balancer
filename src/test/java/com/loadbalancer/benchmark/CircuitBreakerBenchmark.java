package com.loadbalancer.benchmark;

import com.loadbalancer.circuit.CircuitBreaker;
import com.loadbalancer.config.CircuitBreakerConfig;
import org.openjdk.jmh.annotations.*;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for the circuit breaker hot path.
 *
 * <p>Measures the cost of the two key operations:
 * <ul>
 *   <li>{@code canAcceptTraffic()} — called by pool selection on every request</li>
 *   <li>{@code allowRequest()} — called by forwardRequest() on every request</li>
 * </ul>
 *
 * <p>Both are expected to be sub-nanosecond in the CLOSED (happy path) state.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class CircuitBreakerBenchmark {

    private CircuitBreaker closedCircuit;
    private CircuitBreaker openCircuit;

    @Setup
    public void setup() {
        CircuitBreakerConfig config = new CircuitBreakerConfig(
                5, Duration.ofSeconds(60), Duration.ofSeconds(30)
        );

        // CLOSED circuit — the happy path (99.9% of production traffic)
        closedCircuit = new CircuitBreaker("bench-closed", config);

        // OPEN circuit — for measuring rejection overhead
        openCircuit = new CircuitBreaker("bench-open", config);
        for (int i = 0; i < 5; i++) openCircuit.recordFailure();
        // openCircuit is now OPEN
    }

    /** Pool selection check — CLOSED state (happy path). */
    @Benchmark
    @Threads(1)
    public boolean testCanAcceptTrafficClosedSeq() {
        return closedCircuit.canAcceptTraffic();
    }

    /** Pool selection check — CLOSED state, 8 concurrent threads. */
    @Benchmark
    @Threads(8)
    public boolean testCanAcceptTrafficClosedConcurrent() {
        return closedCircuit.canAcceptTraffic();
    }

    /** Forwarding gate — CLOSED state (happy path). */
    @Benchmark
    @Threads(1)
    public boolean testAllowRequestClosedSeq() {
        return closedCircuit.allowRequest();
    }

    /** Forwarding gate — CLOSED state, 8 concurrent threads. */
    @Benchmark
    @Threads(8)
    public boolean testAllowRequestClosedConcurrent() {
        return closedCircuit.allowRequest();
    }

    /** Rejection check — OPEN state (fast-fail path). */
    @Benchmark
    @Threads(1)
    public boolean testCanAcceptTrafficOpenSeq() {
        return openCircuit.canAcceptTraffic();
    }

    /** Rejection check — OPEN state, 8 concurrent threads. */
    @Benchmark
    @Threads(8)
    public boolean testCanAcceptTrafficOpenConcurrent() {
        return openCircuit.canAcceptTraffic();
    }
}
