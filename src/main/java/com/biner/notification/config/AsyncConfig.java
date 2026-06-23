package com.biner.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 애플리케이션의 비동기(@Async) 작업을 위한 별도의 스레드 풀을 설정하는 클래스입니다.
 * 특히 알림(Notification) 발송과 같이, 메인 로직의 흐름을 막지 않고 독립적으로 실행되어야 하는
 * 작업들을 위해 'notificationTaskExecutor'라는 이름의 스레드 풀을 생성합니다.
 */
@Configuration
@EnableAsync // Spring의 @Async 기능을 활성화합니다.
public class AsyncConfig {

    /**
     * 'notificationTaskExecutor'라는 이름으로 커스텀 스레드 풀을 생성하여 빈(Bean)으로 등록합니다.
     * @Async("notificationTaskExecutor") 와 같이 사용하여 이 스레드 풀에서 작업을 실행할 수 있습니다.
     * @return 설정이 완료된 스레드 풀 실행기(Executor)
     */
    @Bean(name = "notificationTaskExecutor")
    public Executor notificationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 기본적으로 유지할 스레드 수
        executor.setCorePoolSize(2);
        // 동시에 실행할 수 있는 최대 스레드 수
        executor.setMaxPoolSize(5);
        // 작업 큐의 크기. 스레드가 모두 사용 중일 때 대기할 수 있는 작업의 수
        executor.setQueueCapacity(50);
        // 생성되는 스레드의 이름 접두사 (로그 분석 시 유용)
        executor.setThreadNamePrefix("Notifier-");
        // 설정 초기화
        executor.initialize();
        return executor;
    }
}
