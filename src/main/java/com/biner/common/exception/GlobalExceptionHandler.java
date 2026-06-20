package com.biner.common.exception;

import com.biner.notification.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * 애플리케이션 전역에서 발생하는 예외를 처리하는 핸들러입니다.
 * @ControllerAdvice 애노테이션을 통해 모든 컨트롤러에서 발생하는 예외를 여기서 중앙 관리합니다.
 * 특히, 처리되지 않은(unhandled) 심각한 예외가 발생했을 때 관리자에게 알림을 보내는 중요한 역할을 합니다.
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    // 관리자에게 알림을 보내기 위한 서비스
    private final NotificationService notificationService;

    public GlobalExceptionHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 애플리케이션 내에서 처리되지 않은 모든 종류의 예외(Exception)를 최종적으로 처리하는 메서드입니다.
     * @ExceptionHandler(Exception.class)는 모든 Exception 타입의 예외가 발생했을 때 이 메서드가 호출되도록 합니다.
     * 
     * @param ex 발생한 예외 객체
     */
    @ExceptionHandler(Exception.class)
    public void handleAllExceptions(Exception ex) {
        // 에러 로그를 기록하여 어떤 예외가 어디서 발생했는지 추적할 수 있도록 합니다.
        log.error("처리되지 않은 예외 발생 (GlobalExceptionHandler에서 포착):", ex);
        
        // 비동기(@Async) 방식으로 관리자에게 텔레그램 등을 통해 긴급 에러 알림을 전송합니다.
        // 이렇게 하면 알림 전송 로직이 현재 스레드를 막지 않아, 시스템의 다른 부분에 미치는 영향을 최소화할 수 있습니다.
        notificationService.sendErrorNotification("시스템에 예기치 않은 오류가 발생했습니다.", ex);
    }
}
