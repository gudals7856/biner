package com.biner.order.event;

/**
 * 주문 전송이 최종적으로 실패했을 때 발행되는 이벤트입니다.
 * PositionService가 수신하여 '주문 대기' 상태를 해제합니다.
 */
public record OrderFailedEvent(
  String symbol,
  String clientOrderId,
  String reason
) {
}
