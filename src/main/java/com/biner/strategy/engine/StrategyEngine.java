package com.biner.strategy.engine;

import com.biner.market.event.CandleClosedEvent;
import com.biner.order.domain.Position;
import com.biner.order.repository.PositionRepository;
import com.biner.strategy.domain.TradeSignal;
import com.biner.strategy.util.Ta4jConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuTenkanSenIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuKijunSenIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class StrategyEngine {

    // =================================================================
    // [리팩토링] 전략 파라미터 상수화 (나중에 이 값들만 튜닝하면 됩니다)
    // =================================================================
    // 일목균형표 (Ichimoku Cloud)
    private static final int ICHIMOKU_TENKAN_SEN_PERIOD = 9;
    private static final int ICHIMOKU_KIJUN_SEN_PERIOD = 26;

    // MACD (Moving Average Convergence Divergence)
    private static final int MACD_SHORT_BAR_COUNT = 12;
    private static final int MACD_LONG_BAR_COUNT = 26;

    // RSI (Relative Strength Index)
    private static final int RSI_BAR_COUNT = 14;
    private static final double RSI_OVERSOLD_THRESHOLD = 45.0;
    private static final double RSI_OVERBOUGHT_THRESHOLD = 55.0;

    // 볼린저 밴드 (Bollinger Bands)
    private static final int BOLLINGER_BAR_COUNT = 20;
    private static final double BOLLINGER_DEVIATION_MULTIPLIER = 2.0;
    private static final double BOLLINGER_TOUCH_BUFFER = 0.01; // 1%

    // DCA (Dollar Cost Averaging)
    private static final BigDecimal BASE_ENTRY_PERCENT = new BigDecimal("0.05");
    private static final BigDecimal DCA_DROP_PERCENT = new BigDecimal("0.015");
    private static final int MAX_DCA_COUNT = 4;

    private static final int MAX_BAR_COUNT = 200;

    private final ApplicationEventPublisher eventPublisher;
    private final PositionRepository positionRepository;
    private final Map<String, BarSeries> seriesMap = new ConcurrentHashMap<>();

    public StrategyEngine(ApplicationEventPublisher eventPublisher, PositionRepository positionRepository) {
        this.eventPublisher = eventPublisher;
        this.positionRepository = positionRepository;
    }

    @EventListener
    @Transactional(readOnly = true)
    public void onCandleClosed(CandleClosedEvent event) {
        String symbol = event.symbol();

        BarSeries series = seriesMap.computeIfAbsent(symbol, k -> {
            BaseBarSeries newSeries = new BaseBarSeries(symbol + "_5m");
            newSeries.setMaximumBarCount(MAX_BAR_COUNT);
            return newSeries;
        });

        series.addBar(Ta4jConverter.convert(event));

        if (series.getBarCount() < 50) {
            return;
        }

        analyzeAndEmitSignal(symbol, series, event);
    }

    private void analyzeAndEmitSignal(String symbol, BarSeries series, CandleClosedEvent currentCandle) {
        Optional<Position> activePositionOpt = positionRepository.findBySymbolAndStatus(symbol, Position.PositionStatus.OPEN);

        if (activePositionOpt.isPresent()) {
            handleExistingPosition(activePositionOpt.get(), currentCandle);
        } else {
            handleNewPosition(symbol, series, currentCandle);
        }
    }

    private void handleNewPosition(String symbol, BarSeries series, CandleClosedEvent currentCandle) {
        // [리팩토링] 각 지표의 조건을 별도 메서드로 호출하여 가독성 향상
        boolean isUptrend = isUptrend(series);
        boolean isMacdPositive = isMacdPositive(series);
        boolean isOversold = isOversold(series);
        boolean touchesLowerBand = touchesLowerBand(series, currentCandle);

        if (log.isDebugEnabled()) {
            log.debug("Indicators [{}]: Uptrend={}, MACD+={}, RSI_Oversold={}, BB_Touch={}",
              symbol, isUptrend, isMacdPositive, isOversold, touchesLowerBand);
        }

        if (isUptrend && isMacdPositive && isOversold && touchesLowerBand) {
            TradeSignal signal = new TradeSignal(
              symbol,
              TradeSignal.SignalType.LONG,
              currentCandle.close(),
              BASE_ENTRY_PERCENT,
              "New LONG Signal"
            );
            log.info("🚀 Emitting LONG signal: {}", signal);
            eventPublisher.publishEvent(signal);
        }
        // (숏 포지션 로직은 생략, 필요시 대칭으로 구현)
    }

    private void handleExistingPosition(Position position, CandleClosedEvent currentCandle) {
        if (position.getDcaCount() >= MAX_DCA_COUNT) {
            return;
        }

        BigDecimal currentPrice = currentCandle.close();
        BigDecimal avgEntryPrice = position.getEntryPrice();

        boolean shouldDca = false;

        if (position.getSide() == Position.PositionSide.LONG) {
            BigDecimal dcaTriggerPrice = avgEntryPrice.multiply(BigDecimal.ONE.subtract(DCA_DROP_PERCENT));
            if (currentPrice.compareTo(dcaTriggerPrice) <= 0) {
                shouldDca = true;
            }
        }

        if (shouldDca) {
            TradeSignal dcaSignal = new TradeSignal(
              position.getSymbol(),
              TradeSignal.SignalType.LONG,
              currentPrice,
              BASE_ENTRY_PERCENT,
              "DCA Triggered"
            );
            log.info("💧 Emitting DCA signal. Current DCA Count: {}/{}", position.getDcaCount(), MAX_DCA_COUNT);
            eventPublisher.publishEvent(dcaSignal);
        }
    }

    // =================================================================
    // [리팩토링] 지표별 계산 로직 분리
    // =================================================================

    private boolean isUptrend(BarSeries series) {
        IchimokuTenkanSenIndicator tenkanSen = new IchimokuTenkanSenIndicator(series, ICHIMOKU_TENKAN_SEN_PERIOD);
        IchimokuKijunSenIndicator kijunSen = new IchimokuKijunSenIndicator(series, ICHIMOKU_KIJUN_SEN_PERIOD);
        return tenkanSen.getValue(series.getEndIndex()).doubleValue() >= kijunSen.getValue(series.getEndIndex()).doubleValue();
    }

    private boolean isMacdPositive(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(closePrice, MACD_SHORT_BAR_COUNT, MACD_LONG_BAR_COUNT);
        return macd.getValue(series.getEndIndex()).doubleValue() >= 0;
    }

    private boolean isOversold(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(closePrice, RSI_BAR_COUNT);
        return rsi.getValue(series.getEndIndex()).doubleValue() < RSI_OVERSOLD_THRESHOLD;
    }

    private boolean touchesLowerBand(BarSeries series, CandleClosedEvent currentCandle) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(closePrice, BOLLINGER_BAR_COUNT);
        StandardDeviationIndicator sd = new StandardDeviationIndicator(closePrice, BOLLINGER_BAR_COUNT);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, sd, series.numOf(BOLLINGER_DEVIATION_MULTIPLIER));

        double lowerBandValue = bbLower.getValue(series.getEndIndex()).doubleValue();
        double buffer = lowerBandValue * BOLLINGER_TOUCH_BUFFER;

        double currentClose = currentCandle.close().doubleValue();
        double currentLow = currentCandle.low().doubleValue();

        return currentClose <= (lowerBandValue + buffer) || currentLow <= (lowerBandValue + buffer);
    }
}