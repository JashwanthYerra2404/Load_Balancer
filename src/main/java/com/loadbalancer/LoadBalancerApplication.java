package com.loadbalancer;

import com.loadbalancer.config.AppConfig;
import com.loadbalancer.config.ConfigLoader;
import com.loadbalancer.health.HealthChecker;
import com.loadbalancer.pool.*;
import com.loadbalancer.proxy.ProxyHandler;
import com.loadbalancer.server.LoadBalancerServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the load balancer application.
 *
 * <p>Equivalent to Go's main.go — this is the composition root that wires
 * together all dependencies but contains no business logic.
 *
 * <p>Startup sequence:
 * <ol>
 *   <li>Parse CLI arguments (--config flag)</li>
 *   <li>Load and validate YAML configuration</li>
 *   <li>Create backend pool using configured algorithm (Strategy pattern)</li>
 *   <li>Populate pool with backends from config</li>
 *   <li>Start background health checker</li>
 *   <li>Create proxy handler with the pool</li>
 *   <li>Create and start HTTP server</li>
 * </ol>
 */
public class LoadBalancerApplication {

    private static final Logger logger = LoggerFactory.getLogger(LoadBalancerApplication.class);

    public static void main(String[] args) {
        try {
            // Parse --config flag (default: configs/config.yaml)
            String configPath = parseConfigPath(args);

            // Load configuration
            AppConfig config = ConfigLoader.load(configPath);
            logger.info("Configuration loaded: path={}, port={}, backends={}, algorithm={}",
                    configPath, config.server().port(),
                    config.backends().size(), config.algorithm());

            // Create the backend pool using the configured algorithm
            BackendPool pool = createPool(config.algorithm());

            // Populate pool with backends from config
            for (var backendCfg : config.backends()) {
                Backend backend = new Backend(
                        backendCfg.url(),
                        backendCfg.name(),
                        backendCfg.weight(),
                        backendCfg.maxConnections()
                );
                pool.addBackend(backend);
            }

            // Start the background health checker
            HealthChecker healthChecker = new HealthChecker(pool, config.healthCheck());
            healthChecker.start();

            // Register health checker shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                healthChecker.stop();
            }, "health-checker-shutdown"));

            // Create proxy handler with retry support
            ProxyHandler proxyHandler = new ProxyHandler(pool, config.retry());

            // Create and start the HTTP server
            LoadBalancerServer server = new LoadBalancerServer(config.server(), proxyHandler);

            logger.info("Load balancer starting: port={}, backends={}, algorithm={}, " +
                            "health_check_interval={}, max_retries={}",
                    config.server().port(), config.backends().size(),
                    config.algorithm(), config.healthCheck().interval(),
                    config.retry().maxRetries());

            server.start();

        } catch (Exception e) {
            logger.error("Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Creates a BackendPool implementation based on the algorithm name.
     *
     * <p>This is the Strategy pattern payoff — the proxy doesn't know
     * or care which algorithm is used. We select the implementation
     * here at startup based on configuration.
     */
    static BackendPool createPool(String algorithm) {
        return switch (algorithm) {
            case AppConfig.ALGORITHM_LEAST_CONNECTIONS -> new LeastConnectionsPool();
            case AppConfig.ALGORITHM_WEIGHTED_ROUND_ROBIN -> new WeightedRoundRobinPool();
            case AppConfig.ALGORITHM_IP_HASH -> new IPHashPool();
            case AppConfig.ALGORITHM_RANDOM -> new RandomPool();
            default -> new RoundRobinPool(); // includes "round_robin" and ""
        };
    }

    /**
     * Parses the config file path from command-line arguments.
     * Supports: --config path/to/config.yaml
     */
    private static String parseConfigPath(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i])) {
                return args[i + 1];
            }
        }
        return "configs/config.yaml";
    }
}
