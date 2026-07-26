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

    private Ichimoku ichimoku = new Ichimoku();
    private Macd macd = new Macd();
    private Rsi rsi = new Rsi();
    private Bollinger bollinger = new Bollinger();
    private Dca dca = new Dca();
    private General general = new General();

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
    }
}