package com.biner.market.websocket;

import com.biner.market.event.CandleClosedEvent;
import com.biner.market.websocket.dto.BinanceKlineMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;

/**
 * 바이낸스 WebSocket으로부터 수신하는 텍스트 메시지를 처리하는 핸들러입니다.
 * TextWebSocketHandler를 상속받아 텍스트 기반의 WebSocket 메시지를 처리합니다.
 */
@Slf4j
@Component
public class BinanceKlineWebSocketHandler extends TextWebSocketHandler {

    // 애플리케이션 이벤트를 발행하기 위한 퍼블리셔
    private final ApplicationEventPublisher eventPublisher;
    
    // JSON 문자열을 Java 객체로 변환하기 위한 ObjectMapper
    private final ObjectMapper objectMapper;

    public BinanceKlineWebSocketHandler(ApplicationEventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * WebSocket 서버로부터 텍스트 메시지를 수신했을 때 호출되는 메서드입니다.
     * @param session 현재 WebSocket 세션
     * @param message 수신된 텍스트 메시지
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            // 디버그 레벨이 활성화된 경우에만 수신된 원본 메시지를 로그로 남깁니다. (성능 저하 방지)
            if (log.isDebugEnabled()) {
                log.debug("🔥 [LIVE DATA] {}", message.getPayload()); 
            }
            
            // 수신된 JSON 문자열을 BinanceKlineMessage DTO 객체로 변환(파싱)합니다.
            BinanceKlineMessage klineMessage = objectMapper.readValue(message.getPayload(), BinanceKlineMessage.class);
            
            // 메시지와 내부 kline 데이터가 정상적으로 파싱되었는지 확인합니다.
            if (klineMessage != null && klineMessage.kline() != null) {
                BinanceKlineMessage.KlineData klineData = klineMessage.kline();
                
                // 현재 구독 중인 캔들이 args 의 시간봉이고, 해당 캔들이 '마감'(isFinal=true)되었을 때만 처리합니다.
                // TODO : main문 파라미터에 시간봉 종류 추가
                if ("1m".equals(klineData.interval()) && klineData.isFinal()) {
                    // 캔들 마감 데이터를 담은 CandleClosedEvent 객체를 생성합니다.
                    CandleClosedEvent event = new CandleClosedEvent(
                            klineData.symbol(),
                            Instant.ofEpochMilli(klineData.startTime()), // 타임스탬프를 Instant 객체로 변환
                            Instant.ofEpochMilli(klineData.closeTime()),
                            klineData.open(),
                            klineData.high(),
                            klineData.low(),
                            klineData.close(),
                            klineData.volume(),
                            klineData.interval()
                    );
                    
                    log.info("🎯캔들 마감 이벤트 발행. 심볼: {}, 종가: {}", event.symbol(), event.close());
                    
                    // 생성된 이벤트를 애플리케이션 전체에 발행(publish)합니다.
                    // 이 이벤트를 구독(listen)하는 다른 컴포넌트(예: 전략 서비스)가 후속 처리를 진행합니다.
                    eventPublisher.publishEvent(event);
                }
            }
        } catch (Exception e) {
            // 메시지 처리 중 예외가 발생하면 경고 로그를 남깁니다.
            log.warn("메시지 처리 실패: {}", message.getPayload(), e);
        }
    }

    /**
     * WebSocket 연결이 성공적으로 수립되었을 때 호출됩니다.
     * @param session 연결된 세션 정보
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("✅ 바이낸스 WebSocket 연결 성공 (세션 ID: {})", session.getId());
    }

    /**
     * WebSocket 연결이 종료되었을 때 호출됩니다.
     * @param session 종료된 세션 정보
     * @param status 종료 상태 (코드, 원인 등)
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("❌ 바이낸스 WebSocket 연결 끊김: 세션 ID {}, 상태: {}", session.getId(), status);
    }
}
