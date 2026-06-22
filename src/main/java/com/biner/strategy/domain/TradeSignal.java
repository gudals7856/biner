package com.biner.strategy.domain;

import java.math.BigDecimal;

/**
 * 전략 엔진(StrategyEngine)이 매매 결정을 내렸을 때, 이를 주문 실행 서비스(OrderExecutionService)나
 * 백테스트의 모의 체결 서비스(MockExecutionService)에 전달하기 위한 신호 객체입니다.
 * 이 신호에는 매매 방향, 가격, 수량, 그리고 왜 그런 결정을 내렸는지에 대한 이유가 포함됩니다.
 * 불변성을 보장하기 위해 record 클래스로 정의되었습니다.
 */
public record TradeSignal(
    // 거래할 코인 심볼 (예: "BTCUSDT")
    String symbol,
    
    // 매매 신호의 종류 (LONG, SHORT 등)
    SignalType type,
    
    // 신호가 발생한 시점의 가격
    BigDecimal price,
    
    // 주문할 수량 또는 비중. 
    // 백테스트에서는 잔고 대비 비중(%)으로, 실제 주문에서는 코인의 개수로 사용될 수 있습니다.
    BigDecimal amount,
    
    // 이 매매 신호가 발생한 이유 (예: "RSI 과매도 및 골든 크로스 발생")
    String reason
) {
    /**
     * 매매 신호의 종류를 정의하는 열거형입니다.
     */
    public enum SignalType {
        LONG,        // 롱(매수) 포지션 진입
        SHORT,       // 숏(매도) 포지션 진입
        CLOSE_LONG,  // 롱 포지션 청산
        CLOSE_SHORT  // 숏 포지션 청산
    }
}
