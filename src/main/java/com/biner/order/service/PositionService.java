package com.biner.order.service;

import com.biner.account.service.AccountService;
import com.biner.common.config.TradingProperties;
import com.biner.notification.service.NotificationService;
import com.biner.order.client.BinanceFuturesClient;
import com.biner.order.domain.ClientOrderIds;
import com.biner.order.domain.Position;
import com.biner.order.event.OrderFailedEvent;
import com.biner.order.event.OrderFilledEvent;
import com.biner.order.event.PlaceOrderEvent;
import com.biner.order.event.PositionChangedEvent;
import com.biner.order.repository.PositionRepository;
import com.biner.strategy.domain.TradeSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 포지션과 관련된 비즈니스 로직과 상태 관리를 책임지는 '매니저' 서비스입니다.
 *
 * <ul>
 *   <li>TradeSignal → 주문 수량 계산 → PlaceOrderEvent 발행</li>
 *   <li>주문을 낸 뒤 체결(OrderFilledEvent)이 올 때까지는 '주문 대기' 상태로 두어 같은 심볼의 중복 주문을 막습니다.</li>
 *   <li>OrderFilledEvent(거래소 체결) 기준으로만 DB 포지션을 변경합니다.</li>
 * </ul>
 */
@Slf4j
@Service
public class PositionService {

  private static final String QUOTE_ASSET = "USDT";
  private static final BigDecimal MIN_BALANCE = BigDecimal.TEN;

  private final PositionRepository positionRepository;
  private final BinanceFuturesClient binanceClient;
  private final ApplicationEventPublisher eventPublisher;
  private final AccountService accountService;
  private final TradingProperties tradingProperties;
  private final NotificationService notificationService;
  private Clock clock = Clock.systemUTC();

  /** 신호 처리 중인 심볼 (동시 처리 방지) */
  private final Set<String> lockedSymbols = ConcurrentHashMap.newKeySet();
  /** 체결을 기다리는 주문 (심볼 → 대기 주문) */
  private final Map<String, PendingOrder> pendingOrders = new ConcurrentHashMap<>();

  record PendingOrder(String clientOrderId, Instant createdAt) {
  }

  public PositionService(PositionRepository positionRepository,
                         BinanceFuturesClient binanceClient,
                         ApplicationEventPublisher eventPublisher,
                         AccountService accountService,
                         TradingProperties tradingProperties,
                         NotificationService notificationService) {
    this.positionRepository = positionRepository;
    this.binanceClient = binanceClient;
    this.eventPublisher = eventPublisher;
    this.accountService = accountService;
    this.tradingProperties = tradingProperties;
    this.notificationService = notificationService;
  }

  /** 테스트용 시계 주입 */
  void setClock(Clock clock) {
    this.clock = clock;
  }

  // =================================================================
  // 신호 처리
  // =================================================================

  /**
   * 전략 엔진에서 발생한 TradeSignal을 수신하여 주문 명령을 발행합니다.
   * 외부 API 호출이 포함되므로 DB 트랜잭션을 열지 않습니다.
   */
  @EventListener
  public void handleTradeSignal(TradeSignal signal) {
    if (signal.symbol().contains("BACKTEST")) {
      return;
    }
    String symbol = signal.symbol();

    if (!lockedSymbols.add(symbol)) {
      log.info("[{}] 다른 신호를 처리 중이므로 이번 신호는 무시합니다: {}", symbol, signal);
      return;
    }

    try {
      if (hasPendingOrder(symbol)) {
        log.info("[{}] 체결 대기 중인 주문이 있어 신호를 무시합니다: {}", symbol, signal);
        return;
      }

      log.info("트레이드 신호 수신: {}", signal);
      Optional<Position> existing = positionRepository.findBySymbolAndStatus(symbol, Position.PositionStatus.OPEN);

      if (signal.type() == TradeSignal.SignalType.CLOSE_LONG || signal.type() == TradeSignal.SignalType.CLOSE_SHORT) {
        handleCloseSignal(signal, existing);
      } else {
        handleEntryOrDcaSignal(signal, existing);
      }
    } catch (Exception e) {
      log.error("[{}] 신호 처리 중 오류 발생", symbol, e);
      notificationService.sendErrorNotification("신호 처리 중 오류: " + symbol, e);
    } finally {
      lockedSymbols.remove(symbol);
    }
  }

  private void handleCloseSignal(TradeSignal signal, Optional<Position> existing) {
    if (existing.isEmpty()) {
      log.warn("청산 신호가 수신되었으나 DB에 열린 포지션이 없습니다: {}", signal);
      return;
    }
    Position position = existing.get();

    // 전략적 청산 전, 거래소에 걸어둔 보호 주문을 먼저 취소합니다.
    // 취소에 실패해도 청산은 진행합니다. (보호 주문은 closePosition=true 이므로 포지션이 0이면 반대 진입하지 않음)
    try {
      log.info("전략적 청산을 위해 기존 보호 주문을 취소했습니다: {}", binanceClient.cancelAllOpenOrders(signal.symbol()));
    } catch (Exception e) {
      log.warn("보호 주문 취소 실패. 청산 주문은 계속 진행합니다.", e);
    }

    String side = (position.getSide() == Position.PositionSide.LONG) ? "SELL" : "BUY";
    String clientOrderId = ClientOrderIds.newId(ClientOrderIds.EXIT_PREFIX);
    registerPending(signal.symbol(), clientOrderId);
    log.info("포지션 청산 주문 발행 ({}): {} {} {}", signal.reason(), side, position.getAmount(), signal.symbol());
    eventPublisher.publishEvent(new PlaceOrderEvent(signal.symbol(), side, "MARKET", position.getAmount(), null,
      true, clientOrderId));
  }

  private void handleEntryOrDcaSignal(TradeSignal signal, Optional<Position> existing) {
    Position.PositionSide targetSide = (signal.type() == TradeSignal.SignalType.LONG)
      ? Position.PositionSide.LONG
      : Position.PositionSide.SHORT;

    if (existing.isPresent() && existing.get().getSide() != targetSide) {
      log.warn("반대 방향 포지션을 보유 중이므로 진입 신호를 무시합니다. 보유: {}, 신호: {}", existing.get().getSide(), signal);
      return;
    }

    if (existing.isEmpty()) {
      try {
        binanceClient.setLeverage(signal.symbol(), tradingProperties.getGeneral().getLeverage());
      } catch (Exception e) {
        log.error("[{}] 레버리지 설정에 실패했습니다. 주문을 진행하지 않습니다.", signal.symbol(), e);
        return;
      }
    }

    BigDecimal availableBalance = accountService.getAvailableBalance(QUOTE_ASSET);
    if (availableBalance.compareTo(MIN_BALANCE) < 0) {
      log.warn("가용 잔고({})가 부족하여 주문을 실행할 수 없습니다.", availableBalance);
      return;
    }

    BigDecimal investmentAmount = availableBalance.multiply(signal.amount());
    BigDecimal quantity = floorToStep(investmentAmount.divide(signal.price(), 8, RoundingMode.DOWN));
    log.info("주문 수량 계산: 잔고 {} USDT * 비율 {} = {} USDT / 현재가 {} = {}개",
      availableBalance.setScale(2, RoundingMode.DOWN), signal.amount(),
      investmentAmount.setScale(2, RoundingMode.DOWN), signal.price(), quantity);

    if (quantity.signum() <= 0) {
      log.warn("계산된 주문 수량이 0이므로 주문을 실행하지 않습니다.");
      return;
    }

    String side = (targetSide == Position.PositionSide.LONG) ? "BUY" : "SELL";
    String clientOrderId = ClientOrderIds.newId(ClientOrderIds.ENTRY_PREFIX);
    registerPending(signal.symbol(), clientOrderId);
    // 물타기 시 보호 주문은 체결 후 새 평단가 기준으로 재설정됩니다(OrderExecutionService).
    log.info("시장가 주문 발행: {} {} {} ({})", side, quantity, signal.symbol(), signal.reason());
    eventPublisher.publishEvent(new PlaceOrderEvent(signal.symbol(), side, "MARKET", quantity, null, false, clientOrderId));
  }

  // =================================================================
  // 체결 / 실패 처리
  // =================================================================

  /**
   * 실제 주문 체결(OrderFilledEvent)을 수신하여 DB의 포지션 상태를 업데이트합니다.
   */
  @EventListener
  @Transactional
  public void handleOrderFilled(OrderFilledEvent event) {
    log.info("주문 체결 수신. DB 상태 업데이트 시작: {}", event);
    clearPending(event.symbol(), event.clientOrderId());

    Optional<Position> existingOpt = positionRepository.findBySymbolAndStatus(event.symbol(), Position.PositionStatus.OPEN);

    if (existingOpt.isEmpty()) {
      openNewPosition(event);
      return;
    }

    Position existing = existingOpt.get();
    if (existing.getSide() != event.side()) {
      applyClosingFill(existing, event);
    } else {
      applyAddingFill(existing, event);
    }
  }

  private void openNewPosition(OrderFilledEvent event) {
    // 포지션이 없는데 청산성 주문이 체결된 경우(예: 이미 청산된 뒤 늦게 도착한 보호 주문),
    // 또는 우리 시스템이 내지 않은 주문은 새 포지션으로 기록하지 않습니다.
    if (event.reduceOnly() || event.closePosition() || !ClientOrderIds.isEntry(event.clientOrderId())) {
      log.warn("열린 포지션이 없는 상태에서 진입 주문이 아닌 체결이 수신되어 무시합니다: {}", event);
      notificationService.sendErrorNotification("관리 대상이 아닌 체결 감지: " + event, new IllegalStateException("unmanaged fill"));
      return;
    }

    Position newPosition = Position.builder()
      .symbol(event.symbol())
      .side(event.side())
      .entryPrice(event.avgPrice())
      .amount(event.filledQty())
      .leverage(tradingProperties.getGeneral().getLeverage())
      .dcaCount(1)
      .build();
    Position saved = positionRepository.save(newPosition);
    log.info("신규 포지션 생성: {}", saved);
    notificationService.sendTradeNotification(saved, "신규 진입");
    eventPublisher.publishEvent(new PositionChangedEvent(saved));
  }

  private void applyClosingFill(Position existing, OrderFilledEvent event) {
    boolean fullyClosed = event.closePosition() || event.filledQty().compareTo(existing.getAmount()) >= 0;
    if (fullyClosed) {
      existing.close();
    } else {
      existing.reduceAmount(event.filledQty());
    }
    positionRepository.save(existing);

    if (existing.getStatus() == Position.PositionStatus.CLOSED) {
      log.info("포지션 청산 완료 ({}): {}", event.originalOrderType(), existing);
      notificationService.sendTradeNotification(existing, "포지션 청산 (" + event.originalOrderType() + ", 체결가 " + event.avgPrice() + ")");
      // 손절/익절 중 한쪽이 체결되면 남은 보호 주문을 즉시 취소합니다.
      try {
        binanceClient.cancelAllOpenOrders(existing.getSymbol());
      } catch (Exception e) {
        log.error("청산 후 남은 보호 주문 취소 실패. 거래소에서 수동 확인이 필요합니다.", e);
        notificationService.sendErrorNotification("청산 후 남은 주문 취소 실패: " + existing.getSymbol(), e);
      }
    } else {
      log.warn("포지션 부분 청산: {} 청산, 남은 수량 {}", event.filledQty(), existing.getAmount());
    }
  }

  private void applyAddingFill(Position existing, OrderFilledEvent event) {
    BigDecimal oldTotalValue = existing.getEntryPrice().multiply(existing.getAmount());
    BigDecimal addedValue = event.avgPrice().multiply(event.filledQty());
    BigDecimal newTotalAmount = existing.getAmount().add(event.filledQty());
    BigDecimal newAvgPrice = oldTotalValue.add(addedValue).divide(newTotalAmount, 8, RoundingMode.HALF_UP);
    int newDcaCount = existing.getDcaCount() + 1;

    existing.updateDca(newAvgPrice, newTotalAmount, newDcaCount, tradingProperties.getDca().getMaxCount());
    Position saved = positionRepository.save(existing);
    log.info("물타기 체결 반영: {}", saved);
    notificationService.sendTradeNotification(saved, "물타기 " + newDcaCount + "차");
    eventPublisher.publishEvent(new PositionChangedEvent(saved));
  }

  /**
   * 주문 전송이 실패하면 대기 상태를 해제하여 다음 신호를 받을 수 있게 합니다.
   */
  @EventListener
  public void handleOrderFailed(OrderFailedEvent event) {
    log.warn("주문 실패 이벤트 수신. 대기 상태를 해제합니다: {}", event);
    clearPending(event.symbol(), event.clientOrderId());
  }

  // =================================================================
  // 대기 주문 관리
  // =================================================================

  boolean hasPendingOrder(String symbol) {
    PendingOrder pending = pendingOrders.get(symbol);
    if (pending == null) {
      return false;
    }
    Duration timeout = Duration.ofSeconds(tradingProperties.getGeneral().getPendingOrderTimeoutSeconds());
    if (Instant.now(clock).isAfter(pending.createdAt().plus(timeout))) {
      log.error("[{}] 주문({})의 체결 이벤트가 {}초 동안 오지 않았습니다. 대기 상태를 해제합니다. 거래소 확인이 필요합니다.",
        symbol, pending.clientOrderId(), timeout.toSeconds());
      notificationService.sendErrorNotification("체결 확인 시간 초과: " + symbol + " " + pending.clientOrderId(),
        new IllegalStateException("pending order timeout"));
      pendingOrders.remove(symbol, pending);
      return false;
    }
    return true;
  }

  private void registerPending(String symbol, String clientOrderId) {
    pendingOrders.put(symbol, new PendingOrder(clientOrderId, Instant.now(clock)));
  }

  private void clearPending(String symbol, String clientOrderId) {
    pendingOrders.computeIfPresent(symbol, (k, v) -> v.clientOrderId().equals(clientOrderId) ? null : v);
  }

  private BigDecimal floorToStep(BigDecimal quantity) {
    BigDecimal step = tradingProperties.getGeneral().getQuantityStepSize();
    return quantity.divide(step, 0, RoundingMode.DOWN).multiply(step).stripTrailingZeros();
  }
}
