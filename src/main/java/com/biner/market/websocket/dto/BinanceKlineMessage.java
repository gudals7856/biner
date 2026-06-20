package com.biner.market.websocket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceKlineMessage(
    // 어떤 형태로 들어오든 대응할 수 있도록 두 가지 필드를 모두 열어둡니다.
    
    // 1. 단일 스트림 (/ws/btcusdt@kline_1m) 형태일 때 사용
    @JsonProperty("e") String eventType,
    @JsonProperty("E") Long eventTime,
    @JsonProperty("s") String symbol,
    @JsonProperty("k") KlineData kline,
    
    // 2. 복합 스트림 (/stream?streams=...) 형태일 때 사용
    @JsonProperty("stream") String stream,
    @JsonProperty("data") KlineEventData data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KlineEventData(
        @JsonProperty("e") String eventType,
        @JsonProperty("E") long eventTime,
        @JsonProperty("s") String symbol,
        @JsonProperty("k") KlineData kline
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KlineData(
        @JsonProperty("t") long startTime,
        @JsonProperty("T") long closeTime,
        @JsonProperty("s") String symbol,
        @JsonProperty("i") String interval,
        @JsonProperty("f") long firstTradeId,
        @JsonProperty("L") long lastTradeId,
        @JsonProperty("o") BigDecimal open,
        @JsonProperty("c") BigDecimal close,
        @JsonProperty("h") BigDecimal high,
        @JsonProperty("l") BigDecimal low,
        @JsonProperty("v") BigDecimal volume,
        @JsonProperty("n") int numberOfTrades,
        @JsonProperty("x") boolean isFinal,
        @JsonProperty("q") BigDecimal quoteAssetVolume,
        @JsonProperty("V") BigDecimal takerBuyBaseAssetVolume,
        @JsonProperty("Q") BigDecimal takerBuyQuoteAssetVolume,
        @JsonProperty("B") String ignore
    ) {}
}
