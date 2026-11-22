package com.biner.order.event;

import java.math.BigDecimal;

/**
 * 특정 주문을 실행하라는 구체적인 명령을 담는 이벤트입니다.
 *
 * @param symbol        주문할 코인 심볼 (예: "BTCUSDT")
 * @param side          주문 방향 ("BUY" 또는 "SELL")
 * @param type          주문 유형 ("MARKET" 등)
 * @param quantity      주문 수량
 * @param price         주문 가격 (시장가 주문 시에는 null)
 * @param reduceOnly    포지션 축소 전용 여부 (청산 주문은 true로 설정해 반대 포지션 진입을 방지)
 * @param clientOrderId 멱등성 보장을 위한 클라이언트 주문 ID (재시도 시 동일 ID 사용)
 */
public record PlaceOrderEvent(
  String symbol,
  String side,
  String type,
  BigDecimal quantity,
  BigDecimal price,
  boolean reduceOnly,
  String clientOrderId
) {
}
