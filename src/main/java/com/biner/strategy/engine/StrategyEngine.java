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

    private final ApplicationEventPublisher eventPublisher;
    private final PositionRepository positionRepository;
    private final Map<String, BarSeries> seriesMap = new ConcurrentHashMap<>();
    
    private static final int MAX_BAR_COUNT = 200;
    
    private static final BigDecimal BASE_ENTRY_PERCENT = new BigDecimal("0.05");
    private static final BigDecimal DCA_DROP_PERCENT = new BigDecimal("0.015");
    private static final int MAX_DCA_COUNT = 4;

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
        int lastIndex = series.getEndIndex();
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        IchimokuTenkanSenIndicator tenkanSen = new IchimokuTenkanSenIndicator(series, 9);
        IchimokuKijunSenIndicator kijunSen = new IchimokuKijunSenIndicator(series, 26);
        boolean isUptrend = tenkanSen.getValue(lastIndex).doubleValue() >= kijunSen.getValue(lastIndex).doubleValue();

        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
        boolean macdPositive = macd.getValue(lastIndex).doubleValue() >= 0;

        RSIIndicator rsi = new RSIIndicator(closePrice, 14);
        double rsiValue = rsi.getValue(lastIndex).doubleValue();

        SMAIndicator sma = new SMAIndicator(closePrice, 20);
        StandardDeviationIndicator sd = new StandardDeviationIndicator(closePrice, 20);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, sd, series.numOf(2));
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, sd, series.numOf(2));

        double currentClose = currentCandle.close().doubleValue();
        double currentLow = currentCandle.low().doubleValue();
        double lowerBand = bbLower.getValue(lastIndex).doubleValue();
        boolean touchesLowerBand = currentClose <= lowerBand * 1.01 || currentLow <= lowerBand * 1.01;
        
        double currentHigh = currentCandle.high().doubleValue();
        double upperBand = bbUpper.getValue(lastIndex).doubleValue();
        boolean touchesUpperBand = currentClose >= upperBand * 0.99 || currentHigh >= upperBand * 0.99;

        if (isUptrend && macdPositive && rsiValue < 45 && touchesLowerBand) {
            TradeSignal signal = new TradeSignal(
                    symbol,
                    TradeSignal.SignalType.LONG,
                    currentCandle.close(),
                    BASE_ENTRY_PERCENT,
                    "New LONG: Uptrend, MACD+, RSI<45, BB Lower Touch"
            );
            log.info("Emitting new entry signal: {}", signal);
            eventPublisher.publishEvent(signal);
        }
        else if (!isUptrend && !macdPositive && rsiValue > 55 && touchesUpperBand) {
             TradeSignal signal = new TradeSignal(
                    symbol,
                    TradeSignal.SignalType.SHORT,
                    currentCandle.close(),
                    BASE_ENTRY_PERCENT,
                    "New SHORT: Downtrend, MACD-, RSI>55, BB Upper Touch"
            );
            log.info("Emitting new entry signal: {}", signal);
            eventPublisher.publishEvent(signal);
        }
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
        } else if (position.getSide() == Position.PositionSide.SHORT) {
            BigDecimal dcaTriggerPrice = avgEntryPrice.multiply(BigDecimal.ONE.add(DCA_DROP_PERCENT));
            if (currentPrice.compareTo(dcaTriggerPrice) >= 0) {
                shouldDca = true;
            }
        }

        if (shouldDca) {
            TradeSignal.SignalType type = position.getSide() == Position.PositionSide.LONG ? 
                                          TradeSignal.SignalType.LONG : TradeSignal.SignalType.SHORT;
            
            TradeSignal dcaSignal = new TradeSignal(
                    position.getSymbol(),
                    type,
                    currentPrice,
                    BASE_ENTRY_PERCENT,
                    "DCA Triggered: Price dropped below " + DCA_DROP_PERCENT.multiply(new BigDecimal("100")) + "%"
            );
            log.info("Emitting DCA (Averaging Down) signal. Current DCA Count: {}/{}", position.getDcaCount(), MAX_DCA_COUNT);
            eventPublisher.publishEvent(dcaSignal);
        }
    }
}
