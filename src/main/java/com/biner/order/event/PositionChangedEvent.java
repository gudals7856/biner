package com.biner.order.event;

import com.biner.order.domain.Position;

/**
 * 포지션이 신규 생성되거나 변경되었음을 알리는 이벤트입니다.
 * 이 이벤트를 수신하여 OCO(손절/익절) 주문을 설정하는 등의 후속 조치를 취합니다.
 *
 * @param position 변경된 포지션의 최신 상태
 */
public record PositionChangedEvent(
        Position position
) {
}
