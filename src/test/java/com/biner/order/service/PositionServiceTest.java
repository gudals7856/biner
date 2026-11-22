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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PositionServiceTest {

  private static final String SYMBOL = "BTCUSDT";

  private PositionRepository repository;
  private BinanceFuturesClient client;
  private ApplicationEventPublisher publisher;
  private AccountService accountService;
  private PositionService service;

  @BeforeEach
  void setUp() {
    repository = mock(PositionRepository.class);
    client = mock(BinanceFuturesClient.class);
    publisher = mock(ApplicationEventPublisher.class);
    accountService = mock(AccountService.class);
    service = new PositionService(repository, client, publisher, accountService, new TradingProperties(),
      mock(NotificationService.class));
    when(repository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));
    when(accountService.getAvailableBalance("USDT")).thenReturn(new BigDecimal("1000"));
  }

  private TradeSignal longSignal() {
    return new TradeSignal(SYMBOL, TradeSignal.SignalType.LONG, new BigDecimal("50000"), new BigDecimal("0.20"), "test");
  }

  private OrderFilledEvent fill(Position.PositionSide side, String qty, String clientId, boolean reduceOnly, boolean closePosition) {
    return new OrderFilledEvent(SYMBOL, side, new BigDecimal(qty), new BigDecimal("50000"), 1L, clientId,
      "MARKET", reduceOnly, closePosition);
  }

  private Position openLong(String amount) {
    return Position.builder().symbol(SYMBOL).side(Position.PositionSide.LONG)
      .entryPrice(new BigDecimal("50000")).amount(new BigDecimal(amount)).leverage(1).dcaCount(1).build();
  }

  private PlaceOrderEvent capturePlacedOrder() {
    ArgumentCaptor<PlaceOrderEvent> captor = ArgumentCaptor.forClass(PlaceOrderEvent.class);
    verify(publisher).publishEvent(captor.capture());
    return captor.getValue();
  }

  @Test
  void 진입_신호는_수량을_스텝단위로_내림하고_clientOrderId를_붙여_주문한다() {
    when(repository.findBySymbolAndStatus(SYMBOL, Position.PositionStatus.OPEN)).thenReturn(Optional.empty());

    service.handleTradeSignal(longSignal());

    PlaceOrderEvent order = capturePlacedOrder();
    // 1000 * 0.2 / 50000 = 0.004
    assertThat(order.quantity()).isEqualByComparingTo("0.004");
    assertThat(order.side()).isEqualTo("BUY");
    assertThat(order.reduceOnly()).isFalse();
    assertThat(ClientOrderIds.isEntry(order.clientOrderId())).isTrue();
    assertThat(order.clientOrderId()).hasSizeLessThanOrEqualTo(36);
  }

  @Test
  void 체결_대기중에는_같은_심볼의_신호를_무시한다() {
    when(repository.findBySymbolAndStatus(SYMBOL, Position.PositionStatus.OPEN)).thenReturn(Optional.empty());

    service.handleTradeSignal(longSignal());
    service.handleTradeSignal(longSignal());

    verify(publisher, times(1)).publishEvent(any(PlaceOrderEvent.class));
  }

  @Test
  void 주문_실패시_대기상태가_풀린다() {
    when(repository.findBySymbolAndStatus(SYMBOL, Position.PositionStatus.OPEN)).thenReturn(Optional.empty());
    service.handleTradeSignal(longSignal());
    String clientId = capturePlacedOrder().clientOrderId();

    service.handleOrderFailed(new OrderFailedEvent(SYMBOL, clientId, "error"));

    assertThat(service.hasPendingOrder(SYMBOL)).isFalse();
  }

  @Test
  void 체결이_오지_않으면_타임아웃_후_대기상태가_풀린다() {
    when(repository.findBySymbolAndStatus(SYMBOL, Position.PositionStatus.OPEN)).thenReturn(Optional.empty());
    service.setClock(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    service.handleTradeSignal(longSignal());
    assertThat(service.hasPendingOrder(SYMBOL)).isTrue();

    service.setClock(Clock.fixed(Instant.parse("2026-01-01T00:01:01Z"), ZoneOffset.UTC));
    assertThat(service.hasPendingOrder(SYMBOL)).isFalse();
  }

  @Test
  void 진입_주문_체결시_포지션을_만들고_보호주문을_요청한다() {
    when(repository.findBySymbolAndStatus(SYMBOL, Position.PositionStatus.OPEN)).thenReturn(Optional.empty());

    service.handleOrderFilled(fill(Position.PositionSide.LONG, "0.004", ClientOrderIds.newId(ClientOrderIds.ENTRY_PREFIX), false, false));

    verify(repository).save(any(Position.class));
    verify(publisher).publishEvent(any(PositionChangedEvent.class));
  }

  @Test
  void 포지션이_없을때_청산성_체결이나_외부_주문은_포지션으로_기록하지_않는다() {
    when(repository.findBySymbolAndStatus(SYMBOL, Position.PositionStatus.OPEN)).thenReturn(Optional.empty());

    service.handleOrderFilled(fill(Position.PositionSide.SHORT, "0.004", ClientOrderIds.newId(ClientOrderIds.STOP_LOSS_PREFIX), false, true));
    service.handleOrderFilled(fill(Position.PositionSide.SHORT, "0.004", ClientOrderIds.newId(ClientOrderIds.EXIT_PREFIX), true, false));
    service.handleOrderFilled(fill(Position.PositionSide.LONG, "0.004", "manual-order", false, false));

    verify(repository, never()).save(any(Position.class));
  }

  @Test
  void 손절_체결시_포지션을_닫고_남은_보호주문을_취소한다() {
    Position position = openLong("0.004");
    when(repository.findBySymbolAndStatus(SYMBOL, Position.PositionStatus.OPEN)).thenReturn(Optional.of(position));

    service.handleOrderFilled(fill(Position.PositionSide.SHORT, "0.004", "algo-triggered", false, true));

    assertThat(position.getStatus()).isEqualTo(Position.PositionStatus.CLOSED);
    verify(client).cancelAllOpenOrders(SYMBOL);
  }

  @Test
  void 부분_청산_체결은_수량만_줄인다() {
    Position position = openLong("0.010");
    when(repository.findBySymbolAndStatus(SYMBOL, Position.PositionStatus.OPEN)).thenReturn(Optional.of(position));

    service.handleOrderFilled(fill(Position.PositionSide.SHORT, "0.004", ClientOrderIds.newId(ClientOrderIds.EXIT_PREFIX), true, false));

    assertThat(position.getStatus()).isEqualTo(Position.PositionStatus.OPEN);
    assertThat(position.getAmount()).isEqualByComparingTo("0.006");
    verify(client, never()).cancelAllOpenOrders(any());
  }

  @Test
  void 전략적_청산은_reduceOnly_시장가로_주문한다() {
    when(repository.findBySymbolAndStatus(SYMBOL, Position.PositionStatus.OPEN)).thenReturn(Optional.of(openLong("0.004")));

    service.handleTradeSignal(new TradeSignal(SYMBOL, TradeSignal.SignalType.CLOSE_LONG, new BigDecimal("51000"), BigDecimal.ONE, "익절"));

    verify(client).cancelAllOpenOrders(SYMBOL);
    PlaceOrderEvent order = capturePlacedOrder();
    assertThat(order.side()).isEqualTo("SELL");
    assertThat(order.reduceOnly()).isTrue();
    assertThat(order.quantity()).isEqualByComparingTo("0.004");
  }

  @Test
  void 반대_방향_포지션_보유중에는_진입하지_않는다() {
    Position shortPosition = Position.builder().symbol(SYMBOL).side(Position.PositionSide.SHORT)
      .entryPrice(new BigDecimal("50000")).amount(new BigDecimal("0.004")).leverage(1).dcaCount(1).build();
    when(repository.findBySymbolAndStatus(SYMBOL, Position.PositionStatus.OPEN)).thenReturn(Optional.of(shortPosition));

    service.handleTradeSignal(longSignal());

    verify(publisher, never()).publishEvent(any(PlaceOrderEvent.class));
    verify(client, never()).setLeverage(eq(SYMBOL), any(Integer.class));
  }
}
