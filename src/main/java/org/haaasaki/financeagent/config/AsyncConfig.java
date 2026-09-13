package org.haaasaki.financeagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步任务线程池。回测在独立线程执行，页面轮询任务状态
 * （见 docs/BACKEND_GUIDE.md 第 9 节：核心 2、最大 4、队列 100）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "backtestExecutor")
    public ThreadPoolTaskExecutor backtestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("backtest-");
        executor.initialize();
        return executor;
    }
}
