package com.jazzlogs.backend.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated pool for Neo4jAsyncSyncExecutor — kept separate from any other
 * async work so a Neo4j slowdown (e.g. Aura in prod) can't starve unrelated
 * @Async work of threads, and vice versa.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "neo4jSyncExecutor")
    public Executor neo4jSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("neo4j-sync-");
        executor.initialize();
        return executor;
    }
}
