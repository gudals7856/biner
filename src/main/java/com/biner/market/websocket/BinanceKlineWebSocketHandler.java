package com.biner.market.websocket;

import com.biner.market.event.CandleClosedEvent;
import com.biner.market.websocket.dto.BinanceKlineMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;

/**
 * 바이낸스 kline WebSocket 메시지를 처리하여, 캔들이 마감될 때 CandleClosedEvent를 발행합니다.
 * 마지막 메시지 수신 시각을 기록하여 MarketWebSocketConfig의 감시 작업이 연결 이상을 감지할 수 있게 합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceKlineWebSocketHandler extends TextWebSocketHandler {

  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;

  /** 구독 시간봉 (MarketWebSocketConfig에서 설정) */
  @Setter
  private String klineInterval;

  /** 마지막 메시지 수신 시각 */
  @Getter
  private volatile Instant lastMessageAt = Instant.EPOCH;

  /** 마지막으로 발행한 캔들의 시작 시각 (중복 발행 방지) */
  private volatile Instant lastPublishedOpenTime = Instant.EPOCH;

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    lastMessageAt = Instant.now();
    try {
      if (log.isTraceEnabled()) {
        log.trace("[LIVE DATA] {}", message.getPayload());
      }

      BinanceKlineMessage klineMessage = objectMapper.readValue(message.getPayload(), BinanceKlineMessage.class);
      if (klineMessage == null || klineMessage.kline() == null) {
        return;
      }
      BinanceKlineMessage.KlineData k = klineMessage.kline();
      if (!klineInterval.equals(k.interval()) || !k.isFinal()) {
        return;
      }

      Instant openTime = Instant.ofEpochMilli(k.startTime());
      if (!openTime.isAfter(lastPublishedOpenTime)) {
        log.debug("이미 처리한 캔들입니다. 무시합니다: {}", openTime);
        return;
      }
      lastPublishedOpenTime = openTime;

      CandleClosedEvent event = new CandleClosedEvent(
        k.symbol(),
        openTime,
        Instant.ofEpochMilli(k.closeTime()),
        k.open(),
        k.high(),
        k.low(),
        k.close(),
        k.volume(),
        k.interval()
      );
      log.info("{} 캔들 마감 이벤트 발행. 심볼: {}, 종가: {}", klineInterval, event.symbol(), event.close());
      eventPublisher.publishEvent(event);
    } catch (Exception e) {
      log.warn("메시지 처리 실패: {}", message.getPayload(), e);
    }
  }

  /** 워밍업 캔들 이후부터 실시간 캔들을 받도록 기준 시각을 설정합니다. */
  public void markPublishedUntil(Instant openTime) {
    lastPublishedOpenTime = openTime;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    lastMessageAt = Instant.now();
    log.info("바이낸스 kline WebSocket 연결 성공 (세션 ID: {})", session.getId());
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    log.error("바이낸스 kline WebSocket 전송 오류 (세션 ID: {})", session.getId(), exception);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    log.warn("바이낸스 kline WebSocket 연결 끊김: 세션 ID {}, 상태: {}. 감시 작업이 재연결합니다.", session.getId(), status);
  }
}
