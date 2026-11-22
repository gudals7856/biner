package com.biner.market.config;

import com.biner.common.config.TradingProperties;
import com.biner.market.client.BinanceMarketDataClient;
import com.biner.market.event.CandleClosedEvent;
import com.biner.market.event.CandleHistoryLoadedEvent;
import com.biner.market.websocket.BinanceKlineWebSocketHandler;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 바이낸스 선물 실시간 kline WebSocket 연결을 관리합니다.
 *
 * <ol>
 *   <li>시작 시 REST로 과거 캔들을 불러와 CandleHistoryLoadedEvent로 지표를 워밍업합니다.</li>
 *   <li>선물 마켓 스트림에 연결합니다. (현물 스트림이 아닌 선물 가격 기준)</li>
 *   <li>감시 작업이 주기적으로 연결 상태와 마지막 수신 시각을 확인하여, 끊기거나 멈추면 누락 캔들을 보충하고 재연결합니다.</li>
 * </ol>
 */
@Slf4j
@Configuration
@Profile("!backtest & !dataloader")
@RequiredArgsConstructor
public class MarketWebSocketConfig {

  private static final Duration WATCHDOG_INTERVAL = Duration.ofSeconds(15);
  private static final Duration STALE_THRESHOLD = Duration.ofSeconds(60);

  @Value("${binance.kline.interval:1m}")
  private String klineInterval;

  @Value("${binance.kline.symbol:BTCUSDT}")
  private String symbol;

  @Value("${binance.ws.market-stream-url:wss://stream.binancefuture.com/ws}")
  private String marketStreamBaseUrl;

  private final BinanceKlineWebSocketHandler klineWebSocketHandler;
  private final BinanceMarketDataClient marketDataClient;
  private final ApplicationEventPublisher eventPublisher;
  private final TradingProperties tradingProperties;

  private final StandardWebSocketClient client = new StandardWebSocketClient();
  private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "kline-ws-watchdog");
    t.setDaemon(true);
    return t;
  });

  private volatile WebSocketSession session;

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    klineWebSocketHandler.setKlineInterval(klineInterval);
    warmUp();
    connect();
    watchdog.scheduleWithFixedDelay(this::checkConnection,
      WATCHDOG_INTERVAL.toSeconds(), WATCHDOG_INTERVAL.toSeconds(), TimeUnit.SECONDS);
  }

  @PreDestroy
  public void stop() {
    watchdog.shutdownNow();
    closeQuietly();
  }

  private void warmUp() {
    try {
      int limit = Math.min(tradingProperties.getGeneral().getMaxBarCount() + 1, 1500);
      List<CandleClosedEvent> history = marketDataClient.getClosedKlines(symbol, klineInterval, limit);
      if (!history.isEmpty()) {
        eventPublisher.publishEvent(new CandleHistoryLoadedEvent(symbol, history));
        klineWebSocketHandler.markPublishedUntil(history.get(history.size() - 1).openTime());
      }
    } catch (Exception e) {
      log.error("과거 캔들 워밍업 실패. 실시간 캔들이 충분히 쌓일 때까지 매매하지 않습니다.", e);
    }
  }

  private synchronized void connect() {
    String streamName = String.format("%s@kline_%s", symbol.toLowerCase(Locale.ROOT), klineInterval);
    String url = marketStreamBaseUrl + "/" + streamName;
    log.info("바이낸스 선물 kline WebSocket 연결 시도: {}", url);
    try {
      session = client.execute(klineWebSocketHandler, url).get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      log.error("kline WebSocket 연결 실패. {}초 후 다시 시도합니다.", WATCHDOG_INTERVAL.toSeconds(), e);
      session = null;
    }
  }

  private void checkConnection() {
    try {
      WebSocketSession current = session;
      boolean stale = Instant.now().isAfter(klineWebSocketHandler.getLastMessageAt().plus(STALE_THRESHOLD));
      if (current == null || !current.isOpen() || stale) {
        log.warn("kline WebSocket 이상 감지 (세션 없음/닫힘/수신 지연={}). 재연결합니다.", stale);
        closeQuietly();
        // 끊겨 있던 동안 놓친 캔들을 REST로 보충한 뒤 재연결합니다.
        warmUp();
        connect();
      }
    } catch (Exception e) {
      log.error("kline WebSocket 감시 작업 오류", e);
    }
  }

  private synchronized void closeQuietly() {
    WebSocketSession current = session;
    session = null;
    if (current != null) {
      try {
        current.close();
      } catch (Exception ignored) {
        // 이미 닫힌 세션
      }
    }
  }
}
