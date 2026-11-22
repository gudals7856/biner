package com.biner.order.service;

import com.biner.common.config.TradingProperties;
import com.biner.notification.service.NotificationService;
import com.biner.order.client.BinanceFuturesClient;
import com.biner.order.domain.ClientOrderIds;
import com.biner.order.domain.Position;
import com.biner.order.event.OrderFailedEvent;
import com.biner.order.event.PlaceOrderEvent;
import com.biner.order.event.PositionChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * PositionService로부터 받은 구체적인 주문 명령을 실제 거래소 API로 전송하는 '손과 발' 서비스입니다.
 * 매매 판단은 하지 않지만, 주문 실패 시 안전 조치(실패 이벤트 발행, 알림, 보호 주문 실패 시 청산)는 책임집니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExecutionService {

  /** 바이낸스 에러 코드: 중복된 clientOrderId (이미 접수된 주문) */
  private static final String DUPLICATE_CLIENT_ORDER_ID_CODE = "-4116";

  private final BinanceFuturesClient binanceClient;
  private final TradingProperties tradingProperties;
  private final ApplicationEventPublisher eventPublisher;
  private final NotificationService notificationService;

  /**
   * 단일 주문 실행 이벤트를 수신하여 바이낸스에 주문을 전송합니다.
   * 체결 결과는 BinanceUserDataStreamListener가 OrderFilledEvent로 알려줍니다.
   */
  @EventListener
  public void executeOrder(PlaceOrderEvent event) {
    log.info("주문 실행 명령 수신: {}", event);
    try {
      String response = binanceClient.placeOrder(event.symbol(), event.side(), event.type(), event.quantity(),
        event.price(), event.reduceOnly(), event.clientOrderId());
      log.info("바이낸스 주문 응답: {}", response);
    } catch (WebClientResponseException e) {
      if (e.getResponseBodyAsString().contains(DUPLICATE_CLIENT_ORDER_ID_CODE)) {
        // 재시도 과정에서 첫 요청이 이미 접수된 경우입니다. 체결은 웹소켓으로 수신됩니다.
        log.warn("이미 접수된 주문입니다(clientOrderId 중복). 성공으로 간주합니다: {}", event.clientOrderId());
        return;
      }
      handleOrderFailure(event, "HTTP " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
    } catch (Exception e) {
      handleOrderFailure(event, e.getMessage(), e);
    }
  }

  private void handleOrderFailure(PlaceOrderEvent event, String reason, Exception e) {
    log.error("주문 실행 최종 실패: {} 원인: {}", event, reason, e);
    eventPublisher.publishEvent(new OrderFailedEvent(event.symbol(), event.clientOrderId(), reason));
    notificationService.sendErrorNotification("주문 실패: " + event.side() + " " + event.quantity() + " " + event.symbol(), e);
  }

  /**
   * 포지션이 생성/변경되어 DB에 커밋된 후, 손절(STOP_MARKET)과 익절(TAKE_PROFIT_MARKET) 보호 주문을 설정합니다.
   * 두 주문 모두 closePosition=true 이므로 한쪽이 체결되어 포지션이 0이 되면 반대 포지션을 열지 않으며,
   * 남은 주문은 PositionService가 청산 체결을 감지한 직후 일괄 취소합니다.
   * 손절 주문 설정에 실패하면 보호받지 못하는 포지션을 남기지 않도록 즉시 시장가 청산합니다(설정으로 끌 수 있음).
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void setupProtectionOrders(PositionChangedEvent event) {
    Position position = event.position();
    log.info("보호 주문(손절/익절) 설정 명령 수신. 포지션: {}", position);

    String oppositeSide = position.getSide() == Position.PositionSide.LONG ? "SELL" : "BUY";
    boolean isLong = position.getSide() == Position.PositionSide.LONG;
    BigDecimal stopLossPercent = tradingProperties.getRiskManagement().getStopLossPercent();
    BigDecimal takeProfitPercent = tradingProperties.getRiskManagement().getTakeProfitPercent();

    BigDecimal stopPrice = roundToTick(position.getEntryPrice().multiply(
      isLong ? BigDecimal.ONE.subtract(stopLossPercent) : BigDecimal.ONE.add(stopLossPercent)));
    BigDecimal takeProfitPrice = roundToTick(position.getEntryPrice().multiply(
      isLong ? BigDecimal.ONE.add(takeProfitPercent) : BigDecimal.ONE.subtract(takeProfitPercent)));

    // 1. 기존 보호 주문 제거 (물타기로 평단가가 바뀐 경우 재설정)
    try {
      binanceClient.cancelAllOpenOrders(position.getSymbol());
    } catch (Exception e) {
      log.warn("기존 대기 주문 취소 실패. 보호 주문 설정은 계속 진행합니다.", e);
    }

    // 2. 손절 주문 (가장 중요)
    try {
      String response = binanceClient.placeClosePositionOrder(position.getSymbol(), oppositeSide, "STOP_MARKET",
        stopPrice, ClientOrderIds.newId(ClientOrderIds.STOP_LOSS_PREFIX));
      log.info("손절(STOP_MARKET) 주문 설정 완료. 발동가 {}: 응답 {}", stopPrice, response);
    } catch (Exception e) {
      log.error("손절 주문 설정 실패! 포지션: {}", position, e);
      notificationService.sendErrorNotification("손절 주문 설정 실패: " + position.getSymbol(), e);
      if (tradingProperties.getRiskManagement().isCloseOnProtectionFailure()) {
        log.error("보호되지 않은 포지션을 남기지 않기 위해 즉시 시장가 청산합니다.");
        eventPublisher.publishEvent(new PlaceOrderEvent(position.getSymbol(), oppositeSide, "MARKET",
          position.getAmount(), null, true, ClientOrderIds.newId(ClientOrderIds.EXIT_PREFIX)));
      }
      return;
    }

    // 3. 익절 주문 (실패해도 손절은 걸려 있으므로 알림만 보냅니다)
    try {
      String response = binanceClient.placeClosePositionOrder(position.getSymbol(), oppositeSide, "TAKE_PROFIT_MARKET",
        takeProfitPrice, ClientOrderIds.newId(ClientOrderIds.TAKE_PROFIT_PREFIX));
      log.info("익절(TAKE_PROFIT_MARKET) 주문 설정 완료. 발동가 {}: 응답 {}", takeProfitPrice, response);
    } catch (Exception e) {
      log.error("익절 주문 설정 실패. 포지션: {}", position, e);
      notificationService.sendErrorNotification("익절 주문 설정 실패: " + position.getSymbol(), e);
    }
  }

  private BigDecimal roundToTick(BigDecimal price) {
    BigDecimal tick = tradingProperties.getGeneral().getPriceTickSize();
    return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick).stripTrailingZeros();
  }
}
