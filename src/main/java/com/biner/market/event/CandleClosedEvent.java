package com.biner.market.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
₩₩₩ * 특정 시간봉(예: 5분봉) 캔들이 완성(마감)되었을 때 애플리케이션 내부에서 발행되는 이벤트 객체입니다.
 * WebSocket을 통해 실시간 데이터를 받다가, 해당 캔들의 시간이 끝나고 새로운 캔들이 시작될 때
 * 이전 캔들의 최종 데이터를 확정하여 이 이벤트를 발생시킵니다.
 * 이 이벤트는 전략(Strategy) 모듈 등에서 수신하여 매매 판단의 기준 데이터로 사용됩니다.
 * (Record 클래스를 사용하여 불변 객체로 데이터를 전달합니다)
 */
public record CandleClosedEvent(
    // 거래되는 코인 심볼 (예: "BTCUSDT")
    String symbol,
    
    // 해당 캔들의 시작 시간
    Instant openTime,
    
    // 해당 캔들의 마감(종료) 시간
    Instant closeTime,
    
    // 시가 (캔들 시작 시점의 가격)
    BigDecimal open,
    
    // 고가 (해당 캔들 시간 내 최고 가격)
    BigDecimal high,
    
    // 저가 (해당 캔들 시간 내 최저 가격)
    BigDecimal low,
    
    // 종가 (캔들 마감 시점의 최종 가격)
    BigDecimal close,
    
    // 해당 캔들 시간 동안의 거래량
    BigDecimal volume,

    // 캔들 시간 간격 (예: "1m", "5m")
    String interval
) {}
