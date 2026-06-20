package com.biner.common.exception;

import com.biner.notification.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * 전역 예외 처리 및 크리티컬 예외 발생 시 관리자 알림(NotificationService) 발송
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    private final NotificationService notificationService;

    public GlobalExceptionHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @ExceptionHandler(Exception.class)
    public void handleAllExceptions(Exception ex) {
        log.error("Unhandled exception caught by GlobalExceptionHandler", ex);
        // 비동기로 텔레그램 관리자에게 긴급 알림 전송
        notificationService.sendErrorNotification("System encountered an unexpected error", ex);
    }
}
