package com.loadbalancer.benchmark;

import com.loadbalancer.pool.Backend;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class HealthCheckerBenchmark {

    private Backend backend;

    @Setup
    public void setup() {
        backend = new Backend("http://localhost:9001", "backend1", 1, 100);
        backend.setAlive(true);
    }

    @Benchmark
    @Threads(1)
    public boolean testIsAliveSeq() {
        return backend.isAlive();
    }

    @Benchmark
    @Threads(8)
    public boolean testIsAliveConcurrent() {
        return backend.isAlive();
    }
}
