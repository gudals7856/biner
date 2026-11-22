package com.biner.order.service;

import com.biner.notification.service.NotificationService;
import com.biner.order.client.BinanceFuturesClient;
import com.biner.order.client.BinanceFuturesClient.UserDataStreamConnection;
import com.biner.order.domain.Position;
import com.biner.order.event.OrderFilledEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 바이낸스 사용자 데이터 웹소켓 스트림을 구독하고,
 * 실시간 주문 체결 정보를 시스템 내부 이벤트(OrderFilledEvent)로 발행하는 서비스입니다. ('귀' 역할)
 *
 * <ul>
 *   <li>연결이 끊길 때까지 블로킹하고, 끊기면 지수 백오프로 재연결합니다. (동시에 연결은 항상 1개)</li>
 *   <li>Listen Key는 30분마다 연장(keepalive)합니다. (유효기간 60분)</li>
 *   <li>재연결 등으로 같은 체결 메시지가 다시 와도 orderId 기준으로 한 번만 처리합니다.</li>
 * </ul>
 */
@Slf4j
@Service
@Profile("!backtest & !dataloader")
public class BinanceUserDataStreamListener {

  private static final long INITIAL_RECONNECT_DELAY_MS = 1_000;
  private static final long MAX_RECONNECT_DELAY_MS = 60_000;
  private static final long KEEPALIVE_INTERVAL_MINUTES = 30;
  private static final int PROCESSED_ORDER_CACHE_SIZE = 1_000;

  private final BinanceFuturesClient binanceClient;
  private final ApplicationEventPublisher eventPublisher;
  private final NotificationService notificationService;
  private final ObjectMapper objectMapper = new ObjectMapper();

  /** 최근 처리한 orderId (중복 체결 이벤트 제거용, LRU) */
  private final Set<Long> processedOrderIds = Collections.newSetFromMap(
    Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
        return size() > PROCESSED_ORDER_CACHE_SIZE;
      }
    })
  );

  private final ScheduledExecutorService keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "listenkey-keepalive");
    t.setDaemon(true);
    return t;
  });

  private volatile boolean running = true;
  private volatile UserDataStreamConnection currentConnection;
  private Thread listenerThread;

  public BinanceUserDataStreamListener(BinanceFuturesClient binanceClient,
                                       ApplicationEventPublisher eventPublisher,
                                       NotificationService notificationService) {
    this.binanceClient = binanceClient;
    this.eventPublisher = eventPublisher;
    this.notificationService = notificationService;
  }

  @PostConstruct
  public void startListening() {
    listenerThread = new Thread(this::listenLoop, "user-data-stream");
    listenerThread.setDaemon(true);
    listenerThread.start();
  }

  @PreDestroy
  public void stop() {
    running = false;
    keepAliveScheduler.shutdownNow();
    UserDataStreamConnection connection = currentConnection;
    if (connection != null) {
      connection.close();
    }
    if (listenerThread != null) {
      listenerThread.interrupt();
    }
  }

  private void listenLoop() {
    long reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
    while (running && !Thread.currentThread().isInterrupted()) {
      ScheduledFuture<?> keepAliveTask = null;
      try {
        log.info("바이낸스 사용자 데이터 스트림 리스너를 시작합니다...");
        String listenKey = binanceClient.getListenKey();

        currentConnection = binanceClient.connectToUserDataStream(listenKey, this::handleWebSocketMessage);
        reconnectDelay = INITIAL_RECONNECT_DELAY_MS;

        keepAliveTask = keepAliveScheduler.scheduleAtFixedRate(this::keepAlive,
          KEEPALIVE_INTERVAL_MINUTES, KEEPALIVE_INTERVAL_MINUTES, TimeUnit.MINUTES);

        // 연결이 끊길 때까지 블로킹합니다.
        currentConnection.closed().join();
        log.warn("사용자 데이터 스트림 연결이 종료되었습니다. 재연결합니다.");
      } catch (Exception e) {
        log.error("사용자 데이터 스트림 오류. {}ms 후 재연결합니다.", reconnectDelay, e);
        if (reconnectDelay >= MAX_RECONNECT_DELAY_MS) {
          notificationService.sendErrorNotification("사용자 데이터 스트림 재연결이 반복 실패하고 있습니다. 체결 정보가 누락될 수 있습니다.", e);
        }
      } finally {
        if (keepAliveTask != null) {
          keepAliveTask.cancel(false);
        }
        currentConnection = null;
      }

      if (!running) {
        break;
      }
      try {
        Thread.sleep(reconnectDelay);
        reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY_MS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
    log.info("사용자 데이터 스트림 리스너가 종료되었습니다.");
  }

  private void keepAlive() {
    try {
      binanceClient.keepAliveListenKey();
    } catch (Exception e) {
      // 연장에 실패하면 Listen Key가 만료될 수 있으므로 연결을 끊어 새 키로 재연결하게 합니다.
      log.error("Listen Key 연장 실패. 재연결을 유도합니다.", e);
      UserDataStreamConnection connection = currentConnection;
      if (connection != null) {
        connection.close();
        connection.closed().complete(null);
      }
    }
  }

  /**
   * 웹소켓으로부터 메시지를 수신할 때마다 호출되는 콜백 메소드입니다.
   */
  void handleWebSocketMessage(String message) {
    try {
      log.debug("웹소켓 메시지 수신: {}", message);
      JsonNode root = objectMapper.readTree(message);
      String eventType = root.path("e").asText();

      if ("listenKeyExpired".equals(eventType)) {
        log.warn("Listen Key가 만료되었습니다. 재연결합니다.");
        UserDataStreamConnection connection = currentConnection;
        if (connection != null) {
          connection.close();
          connection.closed().complete(null);
        }
        return;
      }

      if (!"ORDER_TRADE_UPDATE".equals(eventType)) {
        return;
      }

      JsonNode o = root.get("o");
      if (!"FILLED".equals(o.path("X").asText())) {
        return;
      }

      long orderId = o.path("i").asLong();
      if (!processedOrderIds.add(orderId)) {
        log.info("이미 처리된 체결 이벤트입니다. 무시합니다. orderId={}", orderId);
        return;
      }

      BigDecimal filledQty = new BigDecimal(o.path("z").asText("0"));
      if (filledQty.signum() <= 0) {
        return;
      }

      Position.PositionSide side = "BUY".equalsIgnoreCase(o.path("S").asText())
        ? Position.PositionSide.LONG
        : Position.PositionSide.SHORT;

      OrderFilledEvent filledEvent = new OrderFilledEvent(
        o.path("s").asText(),
        side,
        filledQty,
        new BigDecimal(o.path("ap").asText("0")),
        orderId,
        o.path("c").asText(""),
        o.path("ot").asText(o.path("o").asText("")),
        o.path("R").asBoolean(false),
        o.path("cp").asBoolean(false)
      );

      log.info("주문 체결(FILLED) 감지. 시스템 이벤트를 발행합니다: {}", filledEvent);
      try {
        eventPublisher.publishEvent(filledEvent);
      } catch (Exception e) {
        // 체결은 거래소에서 이미 일어났으므로, DB 반영 실패는 즉시 사람이 확인해야 합니다.
        log.error("체결 이벤트 처리 실패. DB 포지션과 거래소 상태가 다를 수 있습니다: {}", filledEvent, e);
        notificationService.sendErrorNotification("체결 반영 실패(수동 확인 필요): " + filledEvent, e);
      }
    } catch (Exception e) {
      log.error("웹소켓 메시지 처리 중 오류 발생", e);
    }
  }
}
