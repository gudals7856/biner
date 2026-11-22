package com.biner.order.event;

import com.biner.order.domain.Position;

import java.math.BigDecimal;

/**
 * 거래소에서 주문이 완전히 체결(FILLED)되었을 때 발행되는 이벤트입니다.
 * 시스템 포지션 상태의 유일한 진실 공급원(Single Source of Truth)입니다.
 *
 * @param symbol            심볼
 * @param side              체결 방향 (BUY → LONG, SELL → SHORT)
 * @param filledQty         누적 체결 수량
 * @param avgPrice          평균 체결가
 * @param orderId           거래소 주문 ID (중복 이벤트 제거용)
 * @param clientOrderId     클라이언트 주문 ID (우리 시스템이 낸 주문인지 식별)
 * @param originalOrderType 원래 주문 유형 (MARKET, STOP_MARKET, TAKE_PROFIT_MARKET 등)
 * @param reduceOnly        포지션 축소 전용 주문 여부
 * @param closePosition     전체 포지션 청산 주문 여부
 */
public record OrderFilledEvent(
  String symbol,
  Position.PositionSide side,
  BigDecimal filledQty,
  BigDecimal avgPrice,
  long orderId,
  String clientOrderId,
  String originalOrderType,
  boolean reduceOnly,
  boolean closePosition
) {
}
