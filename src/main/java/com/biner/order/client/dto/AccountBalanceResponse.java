package com.biner.order.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 바이낸스 계좌 정보 API (/fapi/v2/account) 응답을 매핑하기 위한 DTO 입니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountBalanceResponse(
        @JsonProperty("assets") List<AssetBalance> assets
) {
}
