package com.biner.order.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * 바이낸스 계좌의 개별 자산 정보를 매핑하기 위한 DTO 입니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssetBalance(
        @JsonProperty("asset") String asset,
        @JsonProperty("availableBalance") BigDecimal availableBalance,
        @JsonProperty("walletBalance") BigDecimal walletBalance
) {
}
