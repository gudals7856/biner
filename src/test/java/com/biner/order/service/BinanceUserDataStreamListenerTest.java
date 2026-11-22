package com.biner.order.service;

import com.biner.notification.service.NotificationService;
import com.biner.order.client.BinanceFuturesClient;
import com.biner.order.domain.Position;
import com.biner.order.event.OrderFilledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class BinanceUserDataStreamListenerTest {

  private ApplicationEventPublisher publisher;
  private BinanceUserDataStreamListener listener;

  @BeforeEach
  void setUp() {
    publisher = mock(ApplicationEventPublisher.class);
    // @PostConstruct는 호출하지 않으므로 실제 연결은 일어나지 않습니다.
    listener = new BinanceUserDataStreamListener(mock(BinanceFuturesClient.class), publisher, mock(NotificationService.class));
  }

  private String orderUpdate(long orderId, String status) {
    return """
      {"e":"ORDER_TRADE_UPDATE","E":1,"T":1,"o":{"s":"BTCUSDT","c":"bnSL-abc","S":"SELL","o":"MARKET",
      "ot":"STOP_MARKET","X":"%s","i":%d,"z":"0.004","ap":"49000.0","R":true,"cp":true}}
      """.formatted(status, orderId);
  }

  @Test
  void FILLED_메시지를_체결_이벤트로_변환한다() {
    listener.handleWebSocketMessage(orderUpdate(10, "FILLED"));

    ArgumentCaptor<OrderFilledEvent> captor = ArgumentCaptor.forClass(OrderFilledEvent.class);
    verify(publisher).publishEvent(captor.capture());
    OrderFilledEvent event = captor.getValue();
    assertThat(event.side()).isEqualTo(Position.PositionSide.SHORT);
    assertThat(event.filledQty()).isEqualByComparingTo("0.004");
    assertThat(event.originalOrderType()).isEqualTo("STOP_MARKET");
    assertThat(event.closePosition()).isTrue();
    assertThat(event.reduceOnly()).isTrue();
    assertThat(event.clientOrderId()).isEqualTo("bnSL-abc");
  }

  @Test
  void 같은_주문의_체결_메시지가_여러번_와도_한번만_처리한다() {
    listener.handleWebSocketMessage(orderUpdate(11, "FILLED"));
    listener.handleWebSocketMessage(orderUpdate(11, "FILLED"));

    verify(publisher, times(1)).publishEvent(any(OrderFilledEvent.class));
  }

  @Test
  void FILLED가_아닌_메시지는_무시한다() {
    listener.handleWebSocketMessage(orderUpdate(12, "NEW"));
    listener.handleWebSocketMessage("{\"e\":\"ACCOUNT_UPDATE\"}");

    verify(publisher, never()).publishEvent(any(OrderFilledEvent.class));
  }
}
