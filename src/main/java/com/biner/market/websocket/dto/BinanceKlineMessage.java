package com.biner.market.websocket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * 바이낸스 WebSocket으로부터 수신하는 실시간 캔들(Kline) 데이터의 JSON 구조를 매핑하기 위한 DTO(Data Transfer Object)입니다.
 * Record 클래스를 사용하여 불변 객체로 데이터를 표현합니다.
 * @JsonIgnoreProperties(ignoreUnknown = true)는 JSON에 있지만 이 클래스에는 정의되지 않은 필드는 무시하도록 설정합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceKlineMessage(
    @JsonProperty("e") String eventType,   // 이벤트 타입 (예: "kline")
    @JsonProperty("E") Long eventTime,     // 이벤트 발생 시간 (타임스탬프)
    @JsonProperty("s") String symbol,      // 코인 심볼 (예: "BTCUSDT")
    @JsonProperty("k") KlineData kline     // 실제 캔들 데이터가 담긴 객체
) {
    /**
     * 캔들(Kline)의 상세 데이터를 담는 내부 레코드 클래스입니다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KlineData(
        @JsonProperty("t") long startTime,         // 캔들 시작 시간 (타임스탬프)
        @JsonProperty("T") long closeTime,         // 캔들 마감 시간 (타임스탬프)
        @JsonProperty("s") String symbol,          // 코인 심볼
        @JsonProperty("i") String interval,        // 캔들 시간 간격 (예: "1m", "5m")
        @JsonProperty("f") long firstTradeId,      // 이 캔들 내의 첫 거래 ID
        @JsonProperty("L") long lastTradeId,       // 이 캔들 내의 마지막 거래 ID
        @JsonProperty("o") BigDecimal open,        // 시가
        @JsonProperty("c") BigDecimal close,       // 종가
        @JsonProperty("h") BigDecimal high,        // 고가
        @JsonProperty("l") BigDecimal low,         // 저가
        @JsonProperty("v") BigDecimal volume,      // 거래량 (Base Asset 기준, 예: BTC)
        @JsonProperty("n") int numberOfTrades,     // 이 캔들 내의 총 거래 횟수
        @JsonProperty("x") boolean isFinal,        // 이 캔들이 마감(확정)되었는지 여부 (true이면 확정)
        @JsonProperty("q") BigDecimal quoteAssetVolume, // 거래량 (Quote Asset 기준, 예: USDT)
        @JsonProperty("V") BigDecimal takerBuyBaseAssetVolume,  // 매수자(Taker)의 Base Asset 거래량
        @JsonProperty("Q") BigDecimal takerBuyQuoteAssetVolume, // 매수자(Taker)의 Quote Asset 거래량
        @JsonProperty("B") String ignore           // 무시해도 되는 필드
    ) {}
}
