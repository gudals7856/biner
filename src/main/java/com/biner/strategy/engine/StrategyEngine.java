package com.biner.strategy.engine;

import com.biner.market.event.CandleClosedEvent;
import com.biner.order.domain.Position;
import com.biner.order.repository.PositionRepository;
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

/**
 * 시장 데이터(캔들)를 바탕으로 기술적 지표를 계산하고,
 * 매수(진입) 또는 물타기(DCA) 시그널을 발생하는 전략 엔진입니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyEngine {

    // =================================================================
    // [전략 파라미터] 나중에 백테스팅 결과를 바탕으로 이 수치들을 튜닝합니다.
    // =================================================================

    // 1. 일목균형표 (Ichimoku Cloud): 중장기 트렌드 파악용
    private static final int ICHIMOKU_TENKAN_SEN_PERIOD = 9; // 전환선 기간
    private static final int ICHIMOKU_KIJUN_SEN_PERIOD = 26; // 기준선 기간

    // 2. MACD (Moving Average Convergence Divergence): 단기 추세 강도 파악용
    private static final int MACD_SHORT_BAR_COUNT = 12;
    private static final int MACD_LONG_BAR_COUNT = 26;

    // 3. RSI (Relative Strength Index): 과매수/과매도 파악용 (역추세 진입 타점)
    private static final int RSI_BAR_COUNT = 14;
    private static final double RSI_OVERSOLD_THRESHOLD = 45.0; // 45 미만일 때 과매도(매수 찬스)로 판단
    private static final double RSI_OVERBOUGHT_THRESHOLD = 55.0;

    // 4. 볼린저 밴드 (Bollinger Bands): 변동성 기반 저점 파악용  >> BollingerTouchStrategy 에서 판별

    // 5. DCA (Dollar Cost Averaging) 물타기 설정
    private static final BigDecimal BASE_ENTRY_PERCENT = new BigDecimal("0.05"); // 시드의 5% 진입
    private static final BigDecimal DCA_DROP_PERCENT = new BigDecimal("0.015");  // 평단가 대비 1.5% 하락 시 물타기
    private static final int MAX_DCA_COUNT = 4; // 최대 물타기 횟수 제한

    // ta4j BarSeries가 메모리에 보관할 최대 캔들 개수
    private static final int MAX_BAR_COUNT = 200;

    private final ApplicationEventPublisher eventPublisher;
    private final PositionRepository positionRepository;
    private final BollingerStrategyFactory bollingerStrategyFactory;

    // 심볼(코인)별로 캔들 데이터를 누적할 메모리 맵 (동시성 제어를 위해 ConcurrentHashMap 사용)
    private final Map<String, BarSeries> seriesMap = new ConcurrentHashMap<>();

    /**
     * 웹소켓을 통해 새로운 캔들이 마감되었을 때 호출되는 이벤트 리스너입니다.
     */
    @EventListener
    @Transactional(readOnly = true)
    public void onCandleClosed(CandleClosedEvent event) {
        String symbol = event.symbol();

        // 1. 해당 심볼의 차트 데이터가 없으면 새로 생성, 있으면 가져옴
        BarSeries series = seriesMap.computeIfAbsent(symbol, k -> {
            BaseBarSeries newSeries = new BaseBarSeries(symbol + "_1m");
            newSeries.setMaximumBarCount(MAX_BAR_COUNT);
            return newSeries;
        });

        // 2. 마감된 캔들 데이터를 ta4j Bar 형식으로 변환하여 차트에 추가
        series.addBar(Ta4jConverter.convert(event));

        // 3. 지표 계산을 위한 최소한의 데이터(캔들 50개)가 모일 때까지 대기
        if (series.getBarCount() < 50) {
            // 현재 몇 개의 캔들이 쌓였는지, 그리고 몇 개가 더 필요한지 INFO 레벨로 강제 출력합니다.
            log.info("BarSeries for {} contains {} bars. {} more bars needed to start analysis.",
              symbol, series.getBarCount(), Math.max(0, 50 - series.getBarCount()));
            return;
        }

        // 4. 데이터가 충분히 모였으면 포지션 상태를 확인하고 시그널 분석 시작
        analyzeAndEmitSignal(symbol, series, event);
    }

    /**
     * 현재 포지션 보유 여부에 따라 진입(New) 로직을 탈지, 물타기(DCA) 로직을 탈지 결정합니다.
     */
    private void analyzeAndEmitSignal(String symbol, BarSeries series, CandleClosedEvent currentCandle) {
        Optional<Position> activePositionOpt = positionRepository.findBySymbolAndStatus(symbol, Position.PositionStatus.OPEN);

        if (activePositionOpt.isPresent()) {
            handleExistingPosition(activePositionOpt.get(), currentCandle); // 이미 물려있다면 DCA 검토
        } else {
            handleNewPosition(symbol, series, currentCandle); // 무포지션이라면 신규 진입 검토
        }
    }

    /**
     * 신규 포지션(LONG) 진입 조건을 검사하고, 조건이 맞으면 시그널을 발생시킵니다.
     */
    private void handleNewPosition(String symbol, BarSeries series, CandleClosedEvent currentCandle) {
        // 모든 기술적 지표 조건 검사
        boolean isUptrend = isUptrend(series);                     // 일목균형표 상 상승장인가?
        boolean isMacdPositive = isMacdPositive(series);           // MACD가 0 이상인가? (단기 추세 살아있음)
        boolean isOversold = isOversold(series);                   // RSI가 과매도 구간(눌림목)인가?
        boolean touchesLowerBand = touchesLowerBand(series, currentCandle); // 현재 가격이 볼린저 밴드 하단을 터치했는가?

        if (log.isDebugEnabled()) {
            log.debug("Indicators [{}]: Uptrend={}, MACD+={}, RSI_Oversold={}, BB_Touch={}",
              symbol, isUptrend, isMacdPositive, isOversold, touchesLowerBand);
        }

        // 4개의 조건이 모두 만족될 때만 LONG(매수) 시그널 발행 (매우 보수적인 진입 타점)
        if (isUptrend && isMacdPositive && isOversold && touchesLowerBand) {
            TradeSignal signal = new TradeSignal(
              symbol,
              TradeSignal.SignalType.LONG,
              currentCandle.close(), // 현재 종가 기준으로 시그널 생성
              BASE_ENTRY_PERCENT,    // 기본 진입 비중 (5%)
              "New LONG Signal"
            );
            log.info("🚀 Emitting LONG signal: {}", signal);
            eventPublisher.publishEvent(signal);
        }
        // (숏 포지션 로직은 생략, 필요시 대칭으로 구현)
    }

    /**
     * 이미 보유 중인 포지션에 대해 손실 중일 경우 물타기(DCA) 시그널을 발생시킵니다.
     */
    private void handleExistingPosition(Position position, CandleClosedEvent currentCandle) {
        // 최대 물타기 횟수에 도달했다면 더 이상 매수하지 않음 (시드 보호)
        if (position.getDcaCount() >= MAX_DCA_COUNT) {
            return;
        }

        BigDecimal currentPrice = currentCandle.close(); // 현재 캔들의 종가
        BigDecimal avgEntryPrice = position.getEntryPrice(); // 현재 보유중인 포지션의 평단가

        boolean shouldDca = false;

        // LONG 포지션인 경우, 평단가 대비 일정 퍼센트(1.5%) 이상 가격이 하락했는지 체크
        if (position.getSide() == Position.PositionSide.LONG) {
            BigDecimal dcaTriggerPrice = avgEntryPrice.multiply(BigDecimal.ONE.subtract(DCA_DROP_PERCENT));
            if (currentPrice.compareTo(dcaTriggerPrice) <= 0) {
                shouldDca = true;
            }
        }

        // 조건 만족 시 물타기 시그널 발행
        if (shouldDca) {
            TradeSignal dcaSignal = new TradeSignal(
              position.getSymbol(),
              TradeSignal.SignalType.LONG,
              currentPrice,
              BASE_ENTRY_PERCENT, // 물타기 시에도 동일한 비중(5%) 사용
              "DCA Triggered"
            );
            log.info("💧 Emitting DCA signal. Current DCA Count: {}/{}", position.getDcaCount(), MAX_DCA_COUNT);
            eventPublisher.publishEvent(dcaSignal);
        }
    }

    // =================================================================
    // [지표 계산 로직] 각 메서드는 마지막 캔들(현재 상태)을 기준으로 참/거짓을 반환합니다.
    // =================================================================

    /**
     * 일목균형표를 이용한 트렌드 판별
     * 전환선(Tenkan-Sen)이 기준선(Kijun-Sen) 위에 있으면 상승 추세로 간주합니다.
     */
    private boolean isUptrend(BarSeries series) {
        // 전환선 기간
        IchimokuTenkanSenIndicator tenkanSen = new IchimokuTenkanSenIndicator(series, ICHIMOKU_TENKAN_SEN_PERIOD);
        // 기준선 기간
        IchimokuKijunSenIndicator kijunSen = new IchimokuKijunSenIndicator(series, ICHIMOKU_KIJUN_SEN_PERIOD);
        return tenkanSen.getValue(series.getEndIndex()).doubleValue() >= kijunSen.getValue(series.getEndIndex()).doubleValue();
    }

    /**
     * MACD 지표 판별
     * MACD 값이 0 이상이면 단기적인 상승 모멘텀이 살아있다고 간주합니다.
     */
    private boolean isMacdPositive(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(closePrice, MACD_SHORT_BAR_COUNT, MACD_LONG_BAR_COUNT);
        return macd.getValue(series.getEndIndex()).doubleValue() >= 0;
    }

    /**
     * RSI 지표 판별
     * RSI가 임계값(45) 미만일 때 가격이 과도하게 하락한 상태(눌림목/과매도)로 간주합니다.
     */
    private boolean isOversold(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(closePrice, RSI_BAR_COUNT);
        return rsi.getValue(series.getEndIndex()).doubleValue() < RSI_OVERSOLD_THRESHOLD;
    }

    /**
     * 볼린저 밴드 하단 터치 판별
     * 현재 캔들의 종가 또는 저가가 볼린저 밴드 하단선(+ 허용 오차) 이하로 내려왔는지 확인하여 저점을 포착합니다.
     */
    private boolean touchesLowerBand(BarSeries series, CandleClosedEvent currentCandle) {
        BollingerTouchStrategy bollingerStrategy = bollingerStrategyFactory.getStrategy(currentCandle.interval());

        return bollingerStrategy.isTouched(series, currentCandle);
    }
}