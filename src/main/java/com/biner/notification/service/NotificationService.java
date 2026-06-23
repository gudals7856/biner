package com.biner.notification.service;

import com.biner.order.domain.Position;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 텔레그램 봇을 통해 관리자에게 중요한 알림(거래 내역, 시스템 에러 등)을 보내는 서비스입니다.
 */
@Slf4j
@Service
public class NotificationService {

    private final WebClient webClient;

    // application.yml 또는 환경변수에서 주입받는 텔레그램 봇 토큰
    @Value("${telegram.bot.token:dummy}")
    private String botToken;

    // application.yml 또는 환경변수에서 주입받는 텔레그램 채팅 ID (메시지를 받을 대상)
    @Value("${telegram.chat.id:dummy}")
    private String chatId;

    public NotificationService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /**
     * 새로운 거래(포지션 진입/종료)가 발생했을 때 알림 메시지를 비동기적으로 보냅니다.
     * @Async("notificationTaskExecutor") 애노테이션을 통해 별도의 스레드 풀에서 실행됩니다.
     * 
     * @param position 거래와 관련된 포지션 정보
     * @param message  알림 메시지 (예: "신규 진입", "익절")
     */
    @Async("notificationTaskExecutor")
    public void sendTradeNotification(Position position, String message) {
        String text = String.format("🚀 [거래 알림] %s\n- 심볼: %s\n- 포지션: %s\n- 평단가: %s\n- 수량: %s", 
                message, position.getSymbol(), position.getSide(), position.getEntryPrice(), position.getAmount());
        sendMessage(text);
    }
    
    /**
     * 시스템에 처리되지 않은 심각한 오류가 발생했을 때 에러 알림을 비동기적으로 보냅니다.
     * 
     * @param errorMessage 요약 에러 메시지
     * @param ex 발생한 예외 객체
     */
    @Async("notificationTaskExecutor")
    public void sendErrorNotification(String errorMessage, Throwable ex) {
        String text = String.format("🚨 [시스템 긴급 에러] 🚨\n- 내용: %s\n- 상세: %s", 
                errorMessage, ex.getMessage());
        sendMessage(text);
    }

    /**
     * 실제로 텔레그램 Bot API를 호출하여 메시지를 전송하는 private 메서드입니다.
     * @param text 전송할 메시지 본문
     */
    private void sendMessage(String text) {
        // 봇 토큰이 설정되지 않은 경우(dummy 값일 경우) 실제 전송을 건너뛰고 로그만 남깁니다.
        if ("dummy".equals(botToken) || "dummy".equals(chatId)) {
            log.info("텔레그램 알림 전송 생략 (설정값 없음): \n---\n{}\n---", text);
            return;
        }

        try {
            // 텔레그램 sendMessage API URL 생성
            String url = String.format("https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s", 
                    botToken, chatId, text);
            
            // WebClient를 사용하여 비동기 GET 요청 전송
            webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(); // 결과가 올 때까지 동기적으로 대기
                    
            log.info("텔레그램 알림 전송 성공.");
        } catch (Exception e) {
            log.error("텔레그램 알림 전송 실패", e);
        }
    }
}
