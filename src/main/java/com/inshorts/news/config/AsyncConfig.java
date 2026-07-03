package com.inshorts.news.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bounded executor for LLM summary enrichment (design §7 bulkhead). Kept separate
 * from the servlet worker threads so a slow/overloaded LLM can never exhaust the
 * web tier; a bounded queue + {@code CallerRunsPolicy} sheds load gracefully
 * instead of growing unboundedly.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "summaryExecutor", destroyMethod = "shutdown")
    public ExecutorService summaryExecutor(NewsProperties props) {
        int concurrency = Math.max(1, props.getLlm().getSummaryConcurrency());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                concurrency, concurrency,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                r -> {
                    Thread t = new Thread(r, "summary-enrich");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
