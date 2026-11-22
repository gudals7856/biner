package com.biner.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConfigurationProperties(prefix = "trading")
@Getter
@Setter
public class TradingProperties {

    private RiskManagement riskManagement = new RiskManagement();
    private Ichimoku ichimoku = new Ichimoku();
    private Macd macd = new Macd();
    private Rsi rsi = new Rsi();
    private Bollinger bollinger = new Bollinger();
    private Dca dca = new Dca();
    private General general = new General();
    private Strategy strategy = new Strategy();

    @Getter
    @Setter
    public static class RiskManagement {
        private BigDecimal stopLossPercent = new BigDecimal("0.02");
        private BigDecimal takeProfitPercent = new BigDecimal("0.04");
        // 손절 보호 주문 설정에 실패하면 즉시 시장가로 청산할지 여부 (보호되지 않은 포지션 방지)
        private boolean closeOnProtectionFailure = true;
    }

    @Getter
    @Setter
    public static class Ichimoku {
        private int tenkanSenPeriod = 9;
        private int kijunSenPeriod = 26;
    }

    @Getter
    @Setter
    public static class Macd {
        private int shortBarCount = 12;
        private int longBarCount = 26;
        private int signalLineCount = 9;
    }

    @Getter
    @Setter
    public static class Rsi {
        private int barCount = 14;
        private double oversoldThreshold = 45.0;
        private double overboughtThreshold = 70.0;
    }

    @Getter
    @Setter
    public static class Bollinger {
        private int barCount = 20;
        private double deviationMultiplier = 2.0;
    }

    @Getter
    @Setter
    public static class Dca {
        private BigDecimal baseEntryPercent = new BigDecimal("0.20");
        private BigDecimal dcaDropPercent = new BigDecimal("0.015");
        private int maxCount = 4;
    }

    @Getter
    @Setter
    public static class General {
        private int maxBarCount = 200;
        private int leverage = 1; // 레버리지 배율 (기본값 1)
        // 가격 최소 단위 (BTCUSDT 선물: 0.1)
        private BigDecimal priceTickSize = new BigDecimal("0.1");
        // 수량 최소 단위 (BTCUSDT 선물: 0.001)
        private BigDecimal quantityStepSize = new BigDecimal("0.001");
        // 주문 전송 후 체결 이벤트를 기다리는 최대 시간(초). 이 시간 동안은 같은 심볼에 새 주문을 내지 않습니다.
        private long pendingOrderTimeoutSeconds = 60;
    }

    /**
     * 전략 세부 기능 on/off 스위치. (코드 주석 처리 대신 설정으로 실험합니다)
     * 기본값은 현재 운용 중인 전략(볼린저 상단 복귀 익절 + 하드 손절/익절만 사용)과 동일합니다.
     */
    @Getter
    @Setter
    public static class Strategy {
        // 익절: RSI 과매수 이탈
        private boolean rsiOverboughtExitEnabled = false;
        // 익절: 볼린저 밴드 상단 밖에서 복귀
        private boolean bollingerUpperExitEnabled = true;
        // 손절: MACD 데드크로스 / 기준선 하향 이탈
        private boolean strategicStopLossEnabled = false;
        // 물타기(DCA)
        private boolean dcaEnabled = false;
    }
}
