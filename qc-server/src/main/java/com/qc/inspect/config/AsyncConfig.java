package com.qc.inspect.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池。
 *
 * <p>目前只有一个用途：企业微信推送。给它单独一个池而不是复用 Boot 默认的
 * {@code applicationTaskExecutor}，理由是故障隔离——推送卡在企业微信的超时上时，
 * 不该连带别的异步任务一起排队。
 *
 * <p>参数是按「一个小厂每天几十条通知」定的：一个线程足够，排队也只是为了
 * 不让审核员等一个 5 秒超时。
 *
 * <p>拒绝策略选 {@code CallerRunsPolicy}，看着反直觉（队列满了就在业务线程里同步发），
 * 但它是在「丢一条消息」和「这次操作慢几秒」之间选了后者：
 * 消息一旦丢了，没人会知道丢过。而这个策略只有在推送连续失败几千条时才会触发，
 * 到了那个地步，操作变慢反而是最容易被发现的信号。
 */
@Configuration
public class AsyncConfig {

    @Bean("noticePushExecutor")
    public ThreadPoolTaskExecutor noticePushExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(500);
        ex.setThreadNamePrefix("notice-push-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 停机时把队列里的发完再退，别把已经答应出去的推送吞掉
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        ex.initialize();
        return ex;
    }
}
