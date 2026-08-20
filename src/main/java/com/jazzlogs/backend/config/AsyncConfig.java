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

    // The default pool for any @Async method that doesn't name an executor
    // (today: ChatRecommendationMemoryService.syncMemoryUpdate) — "taskExecutor"
    // is Spring's own convention for this, resolved automatically without
    // needing @Primary. Without this bean, Spring can't disambiguate between
    // neo4jSyncExecutor and the auto-configured taskScheduler bean (which also
    // implements Executor) and silently falls back to an unpooled
    // SimpleAsyncTaskExecutor (a new thread per call, no queue/cap) instead.
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("async-");
        executor.initialize();
        return executor;
    }
}
