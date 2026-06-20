package com.biner.market.websocket;

import com.biner.market.event.CandleClosedEvent;
import com.biner.market.websocket.dto.BinanceKlineMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;

@Component
public class BinanceKlineWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BinanceKlineWebSocketHandler.class);
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public BinanceKlineWebSocketHandler(ObjectMapper objectMapper, ApplicationEventPublisher eventPublisher) {
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            // 이제 데이터가 잘 들어오는 것을 확인했으므로, 로그 폭탄을 막기 위해 DEBUG 레벨로 내립니다.
            // (필요하면 application.yml에서 로깅 레벨을 INFO로 유지할 수 있습니다.)
            if (log.isDebugEnabled()) {
                log.debug("🔥 [LIVE DATA] {}", message.getPayload()); 
            }
            
            BinanceKlineMessage wrapper = objectMapper.readValue(message.getPayload(), BinanceKlineMessage.class);
            
            if (wrapper != null) {
                BinanceKlineMessage.KlineData klineData = null;
                
                if (wrapper.kline() != null) {
                    klineData = wrapper.kline();
                } 
                else if (wrapper.data() != null && wrapper.data().kline() != null) {
                    klineData = wrapper.data().kline();
                }

                if (klineData != null) {
                    // 원래 목표였던 5분봉(5m)을 타겟으로 원복합니다 (현재 URL을 1m으로 했다면 "1m"으로 매칭됨)
                    // 현재 테스트를 위해 "1m" 또는 "5m" 이면 모두 처리하게 합니다.
                    if (("1m".equals(klineData.interval()) || "5m".equals(klineData.interval())) && klineData.isFinal()) {
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
                        
                        log.info("🎯 {} Candle Closed for {}: Close={}", klineData.interval(), event.symbol(), event.close());
                        eventPublisher.publishEvent(event);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse message: {}", message.getPayload(), e);
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
