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

@Slf4j
@Component
public class BinanceKlineWebSocketHandler extends TextWebSocketHandler {

    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public BinanceKlineWebSocketHandler(ApplicationEventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("🔥 [LIVE DATA] {}", message.getPayload()); 
            }
            
            // 현물 스트림은 wrapper가 없으므로 바로 파싱합니다.
            BinanceKlineMessage klineMessage = objectMapper.readValue(message.getPayload(), BinanceKlineMessage.class);
            
            if (klineMessage != null && klineMessage.kline() != null) {
                BinanceKlineMessage.KlineData klineData = klineMessage.kline();
                
                // 1분봉(1m) 캔들이 마감되었을 때 이벤트 발행
                if ("1m".equals(klineData.interval()) && klineData.isFinal()) {
                    CandleClosedEvent event = new CandleClosedEvent(
                            klineData.symbol(),
                            Instant.ofEpochMilli(klineData.startTime()),
                            Instant.ofEpochMilli(klineData.closeTime()),
                            klineData.open(),
                            klineData.high(),
                            klineData.low(),
                            klineData.close(),
                            klineData.volume()
                    );
                    
                    log.info("🎯 1m Candle Closed for {}: Close={}", event.symbol(), event.close());
                    eventPublisher.publishEvent(event);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to process message: {}", message.getPayload(), e);
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("✅ Connected to Binance WebSocket (Session ID: {})", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("❌ Disconnected from Binance WebSocket: {} with status: {}", session.getId(), status);
    }
}
