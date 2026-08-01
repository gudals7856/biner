package com.biner.strategy.engine;

import com.biner.common.config.TradingProperties;
import com.biner.market.event.CandleClosedEvent;
import com.biner.order.domain.Position;
import com.biner.order.service.PositionProvider;
import com.biner.strategy.domain.TradeSignal;
import com.biner.strategy.indicator.bollinger.BollingerStrategyFactory;
import com.biner.strategy.indicator.bollinger.BollingerTouchStrategy;
import com.biner.strategy.util.Ta4jConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuKijunSenIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuTenkanSenIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시장 데이터(캔들)를 바탕으로 기술적 지표를 계산하고,
 * 매수(진입) 또는 물타기(DCA), 청산(익절/손절) 시그널을 발생하는 전략 엔진입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyEngine {

    private final ApplicationEventPublisher eventPublisher;
    private final PositionProvider positionProvider; // 환경에 맞는 구현체가 주입됩니다.
    private final BollingerStrategyFactory bollingerStrategyFactory;
    private final TradingProperties tradingProperties;

    // 심볼(코인)별 캔들 데이터(BarSeries)를 저장하는 맵. 동시성 문제 방지를 위해 ConcurrentHashMap 사용.
    private final Map<String, BarSeries> seriesMap = new ConcurrentHashMap<>();

    /**
     * 캔들 마감 이벤트(CandleClosedEvent)를 수신하여 전략 분석을 시작하는 메인 리스너입니다.
     * 이 이벤트는 실시간 WebSocket 또는 백테스트의 ReplayEngine으로부터 발생합니다.
     */
    @EventListener
    @Transactional(readOnly = true)
    public void onCandleClosed(CandleClosedEvent event) {
        String symbol = event.symbol();

        // 1. 해당 심볼의 BarSeries를 가져오거나, 없으면 새로 생성합니다.
        BarSeries series = seriesMap.computeIfAbsent(symbol, k -> {
            BaseBarSeries newSeries = new BaseBarSeries(symbol + "_1m");
            newSeries.setMaximumBarCount(tradingProperties.getGeneral().getMaxBarCount());
            return newSeries;
        });

        // 2. 새로 도착한 캔들 데이터를 ta4j 형식으로 변환하여 BarSeries에 추가합니다.
        series.addBar(Ta4jConverter.convert(event));

        // 3. 지표 계산에 필요한 최소한의 캔들(200개)이 쌓일 때까지 대기합니다.
        if (series.getBarCount() < 200) { // 200 이평선 계산을 위해 최소 200개 캔들 필요
            log.info("캔들 데이터 수집 중... [{}]: {}/200개", symbol, series.getBarCount());
            return;
        }

        // 4. 데이터가 충분하면, 현재 포지션 상태를 확인하고 매매 신호 분석을 시작합니다.
        analyzeAndEmitSignal(symbol, series, event);
    }

    /**
     * [실시간/백테스트 공용] 현재 포지션 보유 여부에 따라 신규 진입 또는 물타기 로직을 수행합니다.
     */
    private void analyzeAndEmitSignal(String symbol, BarSeries series, CandleClosedEvent currentCandle) {
        // 백테스트 모드인 경우 MockExecutionService의 가상 포지션을 가져오고,
        // 실시간 모드인 경우 DB에서 실제 포지션을 가져옵니다.
        Optional<Position> activePositionOpt = positionProvider.findOpenPosition(symbol);

        if (activePositionOpt.isPresent()) {
            handleExistingPosition(activePositionOpt.get(), series, currentCandle); // 포지션 보유 중 -> 물타기 검토
        } else {
            handleNewPosition(symbol, series, currentCandle); // 무포지션 -> 신규 진입 검토
        }
    }

    /**
     * 신규 포지션(LONG) 진입 조건을 '점수제'로 판단하여 매수 신호를 발생시킵니다.
     */
    private void handleNewPosition(String symbol, BarSeries series, CandleClosedEvent currentCandle) {
        // 필수 조건: 상승 추세
        if (!isUptrend(series)) {
            log.debug("지표 상태 [{}]: 상승추세=false. 진입 조건 미충족.", symbol);
            return;
        }

        // 선택 조건 점수 계산
        int score = 0;
        boolean isGoldenCross = isMacdGoldenCross(series);
        boolean isOversold = isOversold(series);
        boolean touchesLowerBand = touchesLowerBand(series, currentCandle);

        if (isGoldenCross) score++;
        if (isOversold) score++;
        if (touchesLowerBand) score++;

        if (log.isDebugEnabled()) {
            log.debug("지표 상태 [{}]: 상승추세=true, 선택조건 점수={}/3 (골든크로스={}, 과매도={}, BB터치={})",
              symbol, score, isGoldenCross, isOversold, touchesLowerBand);
        }

        // 필수 조건(상승추세)을 만족하고, 선택 조건 3개 중 2개 이상 만족 시 진입
        if (score >= 2) {
            TradeSignal signal = new TradeSignal(
              symbol,
              TradeSignal.SignalType.LONG,
              currentCandle.close(),
              tradingProperties.getDca().getBaseEntryPercent(),
              "신규 LONG 진입 신호 (Score: " + score + ")"
            );
            log.debug("🚀 매수 신호 발생: {}", signal);
            eventPublisher.publishEvent(signal);
        }
    }

    /**
     * 보유 중인 포지션에 대해 익절, 손절, 또는 물타기(DCA)를 결정합니다.
     */
    private void handleExistingPosition(Position position, BarSeries series, CandleClosedEvent currentCandle) {
        // 백테스트 모드인 경우 OCO 주문체결 체크 로직 강제 실행
        if (position.getSymbol().contains("BACKTEST")) {
            if (checkForMockOcoExit(position, currentCandle)) {
                return;
            }
        }

        // 0. (최우선) 최대 물타기 횟수에 도달한 경우, 본전 탈출을 최우선으로 시도합니다.
        if (position.isMaxDcaReached()) {
            if (currentCandle.close().compareTo(position.getEntryPrice()) >= 0) {
                log.debug("🚨 최대 물타기 포지션 본전 탈출 조건 충족!");
                createAndPublishExitSignal(position, currentCandle.close(), "본전 탈출: 최대 물타기 도달");
                return;
            }
        }

        // 1. 익절 또는 손절 신호가 있는지 먼저 확인합니다.
        boolean exitSignalFound = checkForExitSignal(position, series, currentCandle);
        if (exitSignalFound) {
            return; // 청산 신호가 발생했으면 물타기 로직은 실행하지 않음
        }

        // 2. 청산 신호가 없다면, 물타기(DCA) 조건을 검토합니다.
        // checkForDcaSignal(position, series, currentCandle); // 물타기 기능 비활성화
    }


    private boolean checkForMockOcoExit(Position position, CandleClosedEvent candle) {
        BigDecimal entryPrice = position.getEntryPrice();
        BigDecimal low = candle.low();
        BigDecimal high = candle.high();

        if (position.getSide() == Position.PositionSide.LONG) {
            BigDecimal stopLossPrice = entryPrice.multiply(BigDecimal.ONE.subtract(tradingProperties.getRiskManagement().getStopLossPercent()));
            BigDecimal takeProfitPrice = entryPrice.multiply(BigDecimal.ONE.add(tradingProperties.getRiskManagement().getTakeProfitPercent()));

            if (low.compareTo(stopLossPrice) <= 0) {
                createAndPublishExitSignal(position, stopLossPrice, "Hard Stop-Loss");
                return true;
            }
            if (high.compareTo(takeProfitPrice) >= 0) {
                createAndPublishExitSignal(position, takeProfitPrice, "Hard Take-Profit");
                return true;
            }
        }
        return false;
    }

    /**
     * 보유 중인 포지션에 대한 익절(Take-Profit) 및 손절(Stop-Loss) 조건을 확인합니다.
     * @return 청산 신호가 발생하여 이벤트를 발행했으면 true
     */
    private boolean checkForExitSignal(Position position, BarSeries series, CandleClosedEvent currentCandle) {
        BigDecimal currentPrice = currentCandle.close();
        BigDecimal entryPrice = position.getEntryPrice();

        // 현재가가 평단가보다 높은 경우, 익절 조건만 확인
        if (currentPrice.compareTo(entryPrice) > 0) {
            // ================== 익절(Take-Profit) 로직 ==================
            // 전략 1: RSI가 과매수 구간에서 벗어났을 때 익절 (추천)
            if (isRsiExitingOverbought(series)) {
                createAndPublishExitSignal(position, currentPrice, "익절: RSI 과매수 이탈");
                return true;
            }

            // 전략 2: 볼린저 밴드 상단 밖에서 복귀
            if (isPriceExitingUpperBand(series)) {
                createAndPublishExitSignal(position, currentPrice, "익절: 볼린저 밴드 복귀");
                return true;
            }
        }
//        // 현재가가 평단가보다 낮은 경우, 손절 조건만 확인
//        else if (currentPrice.compareTo(entryPrice) < 0) {
//            // ================== 손절(Stop-Loss) 로직 ==================
//            // 전략 1: MACD 데드크로스 발생 시 손절 (추천)
//            if (isMacdDeadCross(series)) {
//                createAndPublishExitSignal(position, currentPrice, "손절: MACD 데드크로스");
//                return true;
//            }
//
//            // 전략 2: 가격이 기준선(Kijun-sen) 아래로 마감될 때 손절 (강력 추천 - 트레일링 스탑)
//            if (isPriceBelowKijunSen(series)) {
//                createAndPublishExitSignal(position, currentPrice, "손절: 기준선 하향 이탈");
//                return true;
//            }
//        }
        return false;
    }

    /**
     * 물타기(DCA) 조건을 검사하고, 충족 시 신호를 발생시킵니다.
     */
    private void checkForDcaSignal(Position position, BarSeries series, CandleClosedEvent currentCandle) {
        // 최대 물타기 횟수에 도달한 포지션은 더 이상 물타기를 시도하지 않습니다.
        if (position.isMaxDcaReached()) {
            return;
        }
        
        int dcaCount = position.getDcaCount();

        BigDecimal currentPrice = currentCandle.close();
        BigDecimal avgEntryPrice = position.getEntryPrice();

        boolean shouldDca = false;
        if (position.getSide() == Position.PositionSide.LONG) {
            // 현재가가 평단가 대비 설정된 비율(DCA_DROP_PERCENT) 이상 하락했는지 확인
            BigDecimal dcaTriggerPrice = avgEntryPrice.multiply(BigDecimal.ONE.subtract(tradingProperties.getDca().getDcaDropPercent()));
            if (currentPrice.compareTo(dcaTriggerPrice) <= 0) {
                shouldDca = true;
            }
        }
        // (숏 포지션에 대한 물타기 로직은 여기에 추가)

        if (shouldDca) {
            // 물타기 조건이 충족되면, 다시 한번 신규 진입 조건(RSI, 볼린저밴드)을 확인하여 더 안전한 타점에만 물을 탑니다.
            boolean isOversold = isOversold(series);
            boolean touchesLowerBand = touchesLowerBand(series, currentCandle);
            if(isOversold && touchesLowerBand) {
                // 물타기 횟수가 늘어날수록 비중을 줄입니다. (2차: 1/2, 3차: 1/3, 4차: 1/4)
                BigDecimal dcaAmount = tradingProperties.getDca().getBaseEntryPercent()
                        .divide(BigDecimal.valueOf(dcaCount), 8, RoundingMode.HALF_UP);

                TradeSignal dcaSignal = new TradeSignal(
                    position.getSymbol(),
                    TradeSignal.SignalType.LONG,
                    currentPrice,
                    dcaAmount,
                    "DCA(물타기) 신호"
                );
                log.debug("💧 물타기 신호 발생. 현재 DCA 횟수: {}/{}", dcaCount + 1, tradingProperties.getDca().getMaxCount());
                eventPublisher.publishEvent(dcaSignal);
            }
        }
    }

    /**
     * 청산 신호(익절/손절) 객체를 생성하고 이벤트를 발행합니다.
     */
    private void createAndPublishExitSignal(Position position, BigDecimal exitPrice, String reason) {
        TradeSignal.SignalType exitSignalType = (position.getSide() == Position.PositionSide.LONG)
                ? TradeSignal.SignalType.CLOSE_LONG
                : TradeSignal.SignalType.CLOSE_SHORT;

        TradeSignal exitSignal = new TradeSignal(
                position.getSymbol(),
                exitSignalType,
                exitPrice,
                BigDecimal.ONE, // 포지션 전체(100%)를 청산
                reason
        );
        log.debug("🔔 포지션 청산 신호 발생: {}", exitSignal);
        eventPublisher.publishEvent(exitSignal);
    }

    // =================================================================
    // 기술적 지표 계산 로직
    // =================================================================

    /**
     * 일목균형표의 전환선과 기준선, 그리고 장기 이동평균선을 이용하여 현재 추세를 판단합니다.
     * @return 전환선 >= 기준선 이고, 현재가가 200 이평선 위에 있으면 true (강한 상승 추세)
     */
    private boolean isUptrend(BarSeries series) {
        // 1. (기존) 단기 추세 확인: 일목균형표 전환선/기준선
        IchimokuTenkanSenIndicator tenkanSen = new IchimokuTenkanSenIndicator(series, tradingProperties.getIchimoku().getTenkanSenPeriod());
        IchimokuKijunSenIndicator kijunSen = new IchimokuKijunSenIndicator(series, tradingProperties.getIchimoku().getKijunSenPeriod());
        boolean isShortTermUptrend = tenkanSen.getValue(series.getEndIndex()).isGreaterThanOrEqual(kijunSen.getValue(series.getEndIndex()));

        // 2. (NEW) 장기 추세 필터: 200주기 이동평균선
        SMAIndicator longTermSma = new SMAIndicator(new ClosePriceIndicator(series), 200);
        boolean isLongTermUptrend = series.getLastBar().getClosePrice().isGreaterThan(longTermSma.getValue(series.getEndIndex()));

        // 두 조건을 모두 만족해야만 진짜 상승 추세로 인정
        return isShortTermUptrend && isLongTermUptrend;
    }

    /**
     * MACD가 시그널선을 상향 돌파(골든 크로스)했는지 확인합니다.
     * @return (이전 MACD < 이전 Signal) && (현재 MACD >= 현재 Signal) 이면 true
     */
    private boolean isMacdGoldenCross(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(closePrice, tradingProperties.getMacd().getShortBarCount(), tradingProperties.getMacd().getLongBarCount());
        EMAIndicator signalLine = new EMAIndicator(macd, tradingProperties.getMacd().getSignalLineCount());

        int currentIndex = series.getEndIndex();
        if (currentIndex < 1) return false;
        int prevIndex = currentIndex - 1;

        return macd.getValue(prevIndex).isLessThan(signalLine.getValue(prevIndex)) && 
               macd.getValue(currentIndex).isGreaterThanOrEqual(signalLine.getValue(currentIndex));
    }

    /**
     * MACD가 시그널선을 하향 돌파(데드 크로스)했는지 확인합니다.
     */
    private boolean isMacdDeadCross(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(closePrice, tradingProperties.getMacd().getShortBarCount(), tradingProperties.getMacd().getLongBarCount());
        EMAIndicator signalLine = new EMAIndicator(macd, tradingProperties.getMacd().getSignalLineCount());

        int currentIndex = series.getEndIndex();
        if (currentIndex < 1) return false;
        int prevIndex = currentIndex - 1;

        return macd.getValue(prevIndex).isGreaterThan(signalLine.getValue(prevIndex)) && 
               macd.getValue(currentIndex).isLessThanOrEqual(signalLine.getValue(currentIndex));
    }

    /**
     * RSI 지표가 설정된 과매도 임계값보다 낮은지 확인합니다.
     * @return RSI < 45 이면 true (과매도 상태로 간주)
     */
    private boolean isOversold(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(closePrice, tradingProperties.getRsi().getBarCount());
        return rsi.getValue(series.getEndIndex()).doubleValue() < tradingProperties.getRsi().getOversoldThreshold();
    }

    /**
     * RSI 지표가 설정된 과매수 구간에서 이탈했는 지 확인합니다.
     * @return RSI가 과매수 기준선을 하향 돌파할 때 true
     */
    private boolean isRsiExitingOverbought(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(closePrice, tradingProperties.getRsi().getBarCount());
        
        int currentIndex = series.getEndIndex();
        if (currentIndex < 1) return false;
        
        double prevRsi = rsi.getValue(currentIndex - 1).doubleValue();
        double currentRsi = rsi.getValue(currentIndex).doubleValue();
        
        return prevRsi >= tradingProperties.getRsi().getOverboughtThreshold() && 
               currentRsi < tradingProperties.getRsi().getOverboughtThreshold();
    }

    /**
     * 현재 가격이 일목균형표의 기준선(Kijun-sen) 아래로 하락했는지 확인합니다.
     * @return 현재 종가 < 기준선 이면 true
     */
    private boolean isPriceBelowKijunSen(BarSeries series) {
        IchimokuKijunSenIndicator kijunSen = new IchimokuKijunSenIndicator(series, tradingProperties.getIchimoku().getKijunSenPeriod());
        Num kijunSenValue = kijunSen.getValue(series.getEndIndex());
        Num currentClose = series.getLastBar().getClosePrice();
        return currentClose.isLessThan(kijunSenValue);
    }

    /**
     * 현재 가격이 볼린저 밴드 하단을 터치했는지 확인합니다.
     * 세부 로직은 BollingerTouchStrategy에 위임합니다.
     */
    private boolean touchesLowerBand(BarSeries series, CandleClosedEvent currentCandle) {
        BollingerTouchStrategy bollingerStrategy = bollingerStrategyFactory.getStrategy(currentCandle.interval(), series);
        return bollingerStrategy.isTouched(series, currentCandle);
    }

    /**
     * 현재 가격이 볼린저 밴드 상단 밖에서 복귀했는 지 확인합니다.
     * @return 직전 종가가 볼린저 밴드 상단을 초과했다가, 현재 종가가 상단 이하로 다시 진입(하향 돌파)하면 true
     */
    private boolean isPriceExitingUpperBand(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(closePrice, tradingProperties.getBollinger().getBarCount());
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma);
        StandardDeviationIndicator sd = new StandardDeviationIndicator(closePrice, tradingProperties.getBollinger().getBarCount());
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, sd, series.numOf(tradingProperties.getBollinger().getDeviationMultiplier()));

        int currentIndex = series.getEndIndex();
        if (currentIndex < 1) return false;

        Num prevClose = series.getBar(currentIndex - 1).getClosePrice();
        Num currentClose = series.getBar(currentIndex).getClosePrice();
        Num prevUpperBand = bbUpper.getValue(currentIndex - 1);
        Num currentUpperBand = bbUpper.getValue(currentIndex);

        return prevClose.isGreaterThan(prevUpperBand) && currentClose.isLessThanOrEqual(currentUpperBand);
    }
}