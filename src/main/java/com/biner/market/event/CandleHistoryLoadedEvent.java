package com.biner.market.event;

import java.util.List;

/**
 * 애플리케이션 시작 시 과거 캔들을 불러왔을 때 발행되는 이벤트입니다.
 * StrategyEngine은 이 캔들로 지표를 미리 계산(워밍업)하며, 매매 신호는 발생시키지 않습니다.
 *
 * @param symbol  심볼
 * @param candles 오래된 순으로 정렬된 마감 캔들 목록
 */
public record CandleHistoryLoadedEvent(
  String symbol,
  List<CandleClosedEvent> candles
) {
}
