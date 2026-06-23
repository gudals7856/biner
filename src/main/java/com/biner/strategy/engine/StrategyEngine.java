package com.biner.strategy.engine;

import com.biner.backtest.engine.MockExecutionService;
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
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuKijunSenIndicator;
import org.ta4j.core.indicators.ichimoku.IchimokuTenkanSenIndicator;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시장 데이터(캔들)를 바탕으로 기술적 지표를 계산하고,
 * 매수(진입) 또는 물타기(DCA) 시그널을 발생하는 전략 엔진입니다.
 * 이 엔진은 실시간 트레이딩과 백테스팅 양쪽에서 모두 사용됩니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyEngine {

    // =================================================================
    // [전략 파라미터] 이 값들을 조정하여 전략의 성과를 튜닝할 수 있습니다.
    // =================================================================

    // 1. 일목균형표 (Ichimoku Cloud): 중장기 추세 판단용
    private static final int ICHIMOKU_TENKAN_SEN_PERIOD = 9; // 전환선 기간
    private static final int ICHIMOKU_KIJUN_SEN_PERIOD = 26; // 기준선 기간

    // 2. MACD (Moving Average Convergence Divergence): 단기 추세 및 모멘텀 판단용
    private static final int MACD_SHORT_BAR_COUNT = 12; // 단기 이동평균
    private static final int MACD_LONG_BAR_COUNT = 26;  // 장기 이동평균

    // 3. RSI (Relative Strength Index): 과매수/과매도 구간 판단용 (역추세 눌림목 타점)
    private static final int RSI_BAR_COUNT = 14;
    private static final double RSI_OVERSOLD_THRESHOLD = 45.0; // RSI가 45 미만일 때 과매도(매수 고려)로 판단

    // 4. 볼린저 밴드 (Bollinger Bands): 변동성 기반의 지지/저항 판단용 (하단 터치 시 매수 고려)
    // (세부 로직은 BollingerTouchStrategy 클래스에 위임)

    // 5. 리스크 관리 및 자금 관리 (DCA - Dollar Cost Averaging)
    private static final BigDecimal BASE_ENTRY_PERCENT = new BigDecimal("0.05"); // 최초 진입 시 전체 시드의 5% 사용
    private static final BigDecimal DCA_DROP_PERCENT = new BigDecimal("0.015");  // 평단가 대비 1.5% 하락 시 물타기 실행
    private static final int MAX_DCA_COUNT = 4; // 최대 분할매수 횟수 (최초 진입 1 + 물타기 3)

    // 기술적 지표 계산에 사용할 최대 캔들 개수 (메모리 관리)
    private static final int MAX_BAR_COUNT = 200;

    private final ApplicationEventPublisher eventPublisher;
    private final PositionRepository positionRepository;
    private final BollingerStrategyFactory bollingerStrategyFactory;
    private final MockExecutionService mockExecutionService; // 백테스트를 위해 추가

    // 심볼(코인)별 캔들 데이터(BarSeries)를 저장하는 맵. 동시성 문제 방지를 위해 ConcurrentHashMap 사용.
    private final Map<String, BarSeries> seriesMap = new ConcurrentHashMap<>();

    /**
     * 캔들 마감 이벤트(CandleClosedEvent)를 수신하여 전략 분석을 시작하는 메인 리스너입니다.
     * 이 이벤트는 실시간 WebSocket 또는 백테스트의 ReplayEngine으로부터 발생합니다.
     */
    @EventListener
    @Transactional(readOnly = true) // DB 변경이 없으므로 readOnly로 성능 최적화
    public void onCandleClosed(CandleClosedEvent event) {
        String symbol = event.symbol();

        // 1. 해당 심볼의 BarSeries를 가져오거나, 없으면 새로 생성합니다.
        BarSeries series = seriesMap.computeIfAbsent(symbol, k -> {
            BaseBarSeries newSeries = new BaseBarSeries(symbol + "_1m");
            newSeries.setMaximumBarCount(MAX_BAR_COUNT); // 메모리에 최대 200개 캔들만 유지
            return newSeries;
        });

        // 2. 새로 도착한 캔들 데이터를 ta4j 형식으로 변환하여 BarSeries에 추가합니다.
        series.addBar(Ta4jConverter.convert(event));

        // 3. 지표 계산에 필요한 최소한의 캔들(50개)이 쌓일 때까지 대기합니다.
        if (series.getBarCount() < 50) {
            log.info("캔들 데이터 수집 중... [{}]: {}/50개", symbol, series.getBarCount());
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
        Optional<Position> activePositionOpt = symbol.contains("BACKTEST")
                ? mockExecutionService.getCurrentPosition()
                : positionRepository.findBySymbolAndStatus(symbol, Position.PositionStatus.OPEN);

        if (activePositionOpt.isPresent()) {
            handleExistingPosition(activePositionOpt.get(), series, currentCandle); // 포지션 보유 중 -> 물타기 검토
        } else {
            handleNewPosition(symbol, series, currentCandle); // 무포지션 -> 신규 진입 검토
        }
    }

    /**
     * 신규 포지션(LONG) 진입 조건을 종합적으로 판단하여 매수 신호를 발생시킵니다.
     */
    private void handleNewPosition(String symbol, BarSeries series, CandleClosedEvent currentCandle) {
        // 4가지 핵심 진입 조건 검사
        boolean isUptrend = isUptrend(series);                     // 조건 1: 일목균형표상 상승 추세인가?
        boolean isMacdPositive = isMacdPositive(series);           // 조건 2: MACD가 0 이상으로, 단기 상승 모멘텀이 있는가?
        boolean isOversold = isOversold(series);                   // 조건 3: RSI가 과매도 구간으로, 눌림목을 형성했는가?
        boolean touchesLowerBand = touchesLowerBand(series, currentCandle); // 조건 4: 볼린저 밴드 하단을 터치하여 단기 저점일 가능성이 있는가?

        if (log.isDebugEnabled()) {
            log.debug("지표 상태 [{}]: 상승추세={}, MACD긍정={}, 과매도={}, BB터치={}",
              symbol, isUptrend, isMacdPositive, isOversold, touchesLowerBand);
        }

        // 모든 조건이 참일 때만 LONG(매수) 신호를 발행합니다. (매우 보수적인 진입 전략)
        if (isUptrend && isMacdPositive && isOversold && touchesLowerBand) {
            TradeSignal signal = new TradeSignal(
              symbol,
              TradeSignal.SignalType.LONG,
              currentCandle.close(), // 현재 종가를 신호 가격으로 설정
              BASE_ENTRY_PERCENT,    // 설정된 기본 진입 비중 사용
              "신규 LONG 진입 신호"
            );
            log.info("🚀 매수 신호 발생: {}", signal);
            eventPublisher.publishEvent(signal);
        }
    }

    /**
     * 이미 보유 중인 포지션에 대해 물타기(DCA) 조건을 검사합니다.
     */
    private void handleExistingPosition(Position position, BarSeries series, CandleClosedEvent currentCandle) {
        // 백테스트 시에는 MockExecutionService의 dcaCount를 사용합니다.
        int dcaCount = position.getSymbol().contains("BACKTEST")
                ? mockExecutionService.getDcaCount()
                : position.getDcaCount();

        if (dcaCount >= MAX_DCA_COUNT) {
            return; // 최대 물타기 횟수 초과 시 더 이상 진행 안함
        }

        BigDecimal currentPrice = currentCandle.close();
        BigDecimal avgEntryPrice = position.getEntryPrice();

        boolean shouldDca = false;
        if (position.getSide() == Position.PositionSide.LONG) {
            // 현재가가 평단가 대비 설정된 비율(DCA_DROP_PERCENT) 이상 하락했는지 확인
            BigDecimal dcaTriggerPrice = avgEntryPrice.multiply(BigDecimal.ONE.subtract(DCA_DROP_PERCENT));
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
                TradeSignal dcaSignal = new TradeSignal(
                    position.getSymbol(),
                    TradeSignal.SignalType.LONG,
                    currentPrice,
                    BASE_ENTRY_PERCENT, // 물타기 시에도 동일한 비중 사용
                    "DCA(물타기) 신호"
                );
                log.info("💧 물타기 신호 발생. 현재 DCA 횟수: {}/{}", dcaCount, MAX_DCA_COUNT);
                eventPublisher.publishEvent(dcaSignal);
            }
        }
    }

    // =================================================================
    // 기술적 지표 계산 로직 (ta4j 라이브러리 사용)
    // =================================================================

    /**
     * 일목균형표의 전환선과 기준선을 이용하여 현재 추세를 판단합니다.
     * @return 전환선 >= 기준선 이면 true (상승 추세 또는 횡보)
     */
    private boolean isUptrend(BarSeries series) {
        IchimokuTenkanSenIndicator tenkanSen = new IchimokuTenkanSenIndicator(series, ICHIMOKU_TENKAN_SEN_PERIOD);
        IchimokuKijunSenIndicator kijunSen = new IchimokuKijunSenIndicator(series, ICHIMOKU_KIJUN_SEN_PERIOD);
        // 마지막 캔들(현재)의 전환선 값이 기준선 값보다 크거나 같은지 확인
        return tenkanSen.getValue(series.getEndIndex()).doubleValue() >= kijunSen.getValue(series.getEndIndex()).doubleValue();
    }

    /**
     * MACD 지표가 0 이상인지 확인하여 단기 상승 모멘텀을 판단합니다.
     * @return MACD >= 0 이면 true
     */
    private boolean isMacdPositive(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(closePrice, MACD_SHORT_BAR_COUNT, MACD_LONG_BAR_COUNT);
        return macd.getValue(series.getEndIndex()).doubleValue() >= 0;
    }

    /**
     * RSI 지표가 설정된 과매도 임계값보다 낮은지 확인합니다.
     * @return RSI < 45 이면 true (과매도 상태로 간주)
     */
    private boolean isOversold(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(closePrice, RSI_BAR_COUNT);
        return rsi.getValue(series.getEndIndex()).doubleValue() < RSI_OVERSOLD_THRESHOLD;
    }

    /**
     * 현재 가격이 볼린저 밴드 하단을 터치했는지 확인합니다.
     * 세부 로직은 BollingerTouchStrategy에 위임합니다.
     */
    private boolean touchesLowerBand(BarSeries series, CandleClosedEvent currentCandle) {
        // 캔들 인터벌(1m, 5m 등)에 맞는 볼린저밴드 전략 객체를 가져옴
        BollingerTouchStrategy bollingerStrategy = bollingerStrategyFactory.getStrategy(currentCandle.interval());
        return bollingerStrategy.isTouched(series, currentCandle);
    }
}
