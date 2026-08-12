package com.loadbalancer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Path writeConfig(String content) throws IOException {
        Path path = tempDir.resolve("config.yaml");
        Files.writeString(path, content);
        return path;
    }

    @Test
    void validConfigParsesCorrectly() throws IOException {
        Path path = writeConfig("""
                server:
                  port: 9090
                  read_timeout: 10s
                  write_timeout: 20s
                  idle_timeout: 120s
                algorithm: least_connections
                backends:
                  - url: "http://localhost:9001"
                    name: "backend-1"
                    weight: 3
                    max_connections: 100
                  - url: "http://localhost:9002"
                    name: "backend-2"
                    weight: 2
                """);

        AppConfig cfg = ConfigLoader.load(path.toString());

        assertEquals(9090, cfg.server().port());
        assertEquals(Duration.ofSeconds(10), cfg.server().readTimeout());
        assertEquals(Duration.ofSeconds(20), cfg.server().writeTimeout());
        assertEquals(Duration.ofSeconds(120), cfg.server().idleTimeout());
        assertEquals("least_connections", cfg.algorithm());
        assertEquals(2, cfg.backends().size());

        assertEquals("http://localhost:9001", cfg.backends().get(0).url());
        assertEquals("backend-1", cfg.backends().get(0).name());
        assertEquals(3, cfg.backends().get(0).weight());
        assertEquals(100, cfg.backends().get(0).maxConnections());
    }

    @Test
    void defaultsAppliedForMissingValues() throws IOException {
        Path path = writeConfig("""
                backends:
                  - url: "http://localhost:9001"
                    name: "b1"
                """);

        AppConfig cfg = ConfigLoader.load(path.toString());

        assertEquals(ServerConfig.DEFAULT_PORT, cfg.server().port());
        assertEquals(ServerConfig.DEFAULT_READ_TIMEOUT, cfg.server().readTimeout());
        assertEquals(AppConfig.DEFAULT_ALGORITHM, cfg.algorithm());
    }

    @Test
    void defaultAlgorithmIsRoundRobin() throws IOException {
        Path path = writeConfig("""
                backends:
                  - url: "http://localhost:9001"
                    name: "b1"
                """);

        AppConfig cfg = ConfigLoader.load(path.toString());
        assertEquals("round_robin", cfg.algorithm());
    }

    @Test
    void allValidAlgorithmsAccepted() throws IOException {
        for (String algo : AppConfig.VALID_ALGORITHMS) {
            Path path = writeConfig(String.format("""
                    algorithm: %s
                    backends:
                      - url: "http://localhost:9001"
                        name: "b1"
                    """, algo));
            AppConfig cfg = ConfigLoader.load(path.toString());
            assertEquals(algo, cfg.algorithm());
        }
    }

    @Test
    void invalidAlgorithmRejected() throws IOException {
        Path path = writeConfig("""
                algorithm: fastest_response
                backends:
                  - url: "http://localhost:9001"
                    name: "b1"
                """);

        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(path.toString()));
    }

    @Test
    void emptyBackendsRejected() throws IOException {
        Path path = writeConfig("""
                server:
                  port: 8080
                backends: []
                """);

        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(path.toString()));
    }

    @Test
    void missingBackendNameRejected() throws IOException {
        Path path = writeConfig("""
                backends:
                  - url: "http://localhost:9001"
                """);

        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(path.toString()));
    }

    @Test
    void duplicateBackendNamesRejected() throws IOException {
        Path path = writeConfig("""
                backends:
                  - url: "http://localhost:9001"
                    name: "same"
                  - url: "http://localhost:9002"
                    name: "same"
                """);

        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(path.toString()));
    }

    @Test
    void invalidBackendSchemeRejected() throws IOException {
        Path path = writeConfig("""
                backends:
                  - url: "ftp://localhost:9001"
                    name: "bad-scheme"
                """);

        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(path.toString()));
    }

    @Test
    void healthCheckDefaultsApplied() throws IOException {
        Path path = writeConfig("""
                backends:
                  - url: "http://localhost:9001"
                    name: "b1"
                """);

        AppConfig cfg = ConfigLoader.load(path.toString());
        assertNotNull(cfg.healthCheck());
        assertEquals(HealthCheckConfig.DEFAULT_INTERVAL, cfg.healthCheck().interval());
        assertEquals(HealthCheckConfig.DEFAULT_TIMEOUT, cfg.healthCheck().timeout());
        assertEquals(HealthCheckConfig.DEFAULT_PATH, cfg.healthCheck().path());
        assertEquals(HealthCheckConfig.DEFAULT_FAILURE_THRESHOLD, cfg.healthCheck().failureThreshold());
        assertEquals(HealthCheckConfig.DEFAULT_SUCCESS_THRESHOLD, cfg.healthCheck().successThreshold());
    }

    @Test
    void healthCheckCustomValuesPreserved() throws IOException {
        Path path = writeConfig("""
                health_check:
                  interval: 30s
                  timeout: 10s
                  path: "/status"
                  failure_threshold: 5
                  success_threshold: 2
                backends:
                  - url: "http://localhost:9001"
                    name: "b1"
                """);

        AppConfig cfg = ConfigLoader.load(path.toString());
        assertEquals(Duration.ofSeconds(30), cfg.healthCheck().interval());
        assertEquals(Duration.ofSeconds(10), cfg.healthCheck().timeout());
        assertEquals("/status", cfg.healthCheck().path());
        assertEquals(5, cfg.healthCheck().failureThreshold());
        assertEquals(2, cfg.healthCheck().successThreshold());
    }

    @Test
    void fileNotFoundThrows() {
        assertThrows(IOException.class, () -> ConfigLoader.load("/nonexistent/config.yaml"));
    }

    @Test
    void malformedYamlThrowsConfigValidationException() throws IOException {
        Path path = writeConfig("""
                server:
                  port: 8080
                  - invalid_yaml: [
                """);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(path.toString()));
    }

    @Test
    void duplicateUrlsRejected() throws IOException {
        Path path = writeConfig("""
                backends:
                  - url: "http://10.0.0.1:8080"
                    name: "b1"
                  - url: "http://10.0.0.1:8080"
                    name: "b2"
                """);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(path.toString()));
    }

    @Test
    void stringRepresentationsOfIntegersParsed() throws IOException {
        Path path = writeConfig("""
                server:
                  port: "9090"
                backends:
                  - url: "http://localhost:9001"
                    name: "b1"
                    weight: "5"
                """);
        AppConfig cfg = ConfigLoader.load(path.toString());
        assertEquals(9090, cfg.server().port());
        assertEquals(5, cfg.backends().get(0).weight());
    }

    @Test
    void negativeWeightPreservedForValidation() throws IOException {
        Path path = writeConfig("""
                backends:
                  - url: "http://localhost:9001"
                    name: "b1"
                    weight: -5
                """);
        assertThrows(ConfigValidationException.class, () -> ConfigLoader.load(path.toString()));
    }
}
