package com.biner.market.websocket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceKlineMessage(
    // [수정] 현물 스트림은 wrapper 없이 바로 내려오므로, stream/data 필드를 제거하고 원래 구조로 복구합니다.
    @JsonProperty("e") String eventType,
    @JsonProperty("E") Long eventTime,
    @JsonProperty("s") String symbol,
    @JsonProperty("k") KlineData kline
) {
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
