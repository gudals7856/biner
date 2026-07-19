package com.biner.order.event;

import java.math.BigDecimal;

/**
 * 특정 주문을 실행하라는 구체적인 명령을 담는 이벤트입니다.
 *
 * @param symbol   주문할 코인 심볼 (예: "BTCUSDT")
 * @param side     주문 방향 ("BUY" 또는 "SELL")
 * @param type     주문 유형 ("MARKET", "LIMIT", "STOP_MARKET" 등)
 * @param quantity 주문 수량
 * @param price    주문 가격 (시장가 주문 시에는 null)
 */
public record PlaceOrderEvent(
        String symbol,
        String side,
        String type,
        BigDecimal quantity,
        BigDecimal price
) {
}
