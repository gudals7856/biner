package com.biner.order.event;

import com.biner.order.domain.Position;

import java.math.BigDecimal;

/**
 * 주문이 성공적으로 체결되었을 때 발행되는 이벤트입니다.
 * 실제 체결된 가격과 수량을 담고 있습니다.
 */
public record OrderFilledEvent(
        String symbol,
        Position.PositionSide side,
        BigDecimal filledQty,
        BigDecimal avgPrice
) {
}