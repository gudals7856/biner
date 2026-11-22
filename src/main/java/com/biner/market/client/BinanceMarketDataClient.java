package com.biner.market.client;

import com.biner.common.util.Intervals;
import com.biner.market.event.CandleClosedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 인증이 필요 없는 바이낸스 선물 시세 REST API 클라이언트입니다.
 * 애플리케이션 시작 시 지표 계산에 필요한 과거 캔들을 미리 불러오는 데 사용합니다.
 */
@Slf4j
@Component
public class BinanceMarketDataClient {

  private final WebClient webClient;

  @Value("${binance.api.url:https://testnet.binancefuture.com}")
  private String baseUrl;

  public BinanceMarketDataClient(WebClient.Builder webClientBuilder) {
    this.webClient = webClientBuilder.build();
  }

  /**
   * 최근 마감된 캔들을 조회합니다. 아직 마감되지 않은 마지막 캔들은 제외합니다.
   *
   * @param symbol   심볼 (예: "BTCUSDT")
   * @param interval 시간봉 (예: "1m")
   * @param limit    조회 개수 (최대 1500)
   */
  @Retry(name = "binanceApi")
  public List<CandleClosedEvent> getClosedKlines(String symbol, String interval, int limit) {
    String url = String.format("%s/fapi/v1/klines?symbol=%s&interval=%s&limit=%d", baseUrl, symbol, interval, limit);
    JsonNode rows = webClient.get().uri(URI.create(url)).retrieve().bodyToMono(JsonNode.class).block();

    List<CandleClosedEvent> candles = new ArrayList<>();
    if (rows == null || !rows.isArray()) {
      return candles;
    }
    Instant now = Instant.now();
    for (JsonNode row : rows) {
      Instant closeTime = Instant.ofEpochMilli(row.get(6).asLong());
      if (!closeTime.isBefore(now)) {
        continue; // 진행 중인 캔들
      }
      candles.add(new CandleClosedEvent(
        symbol,
        Instant.ofEpochMilli(row.get(0).asLong()),
        closeTime,
        new BigDecimal(row.get(1).asText()),
        new BigDecimal(row.get(2).asText()),
        new BigDecimal(row.get(3).asText()),
        new BigDecimal(row.get(4).asText()),
        new BigDecimal(row.get(5).asText()),
        interval
      ));
    }
    log.info("과거 캔들 {}개 조회 완료 ({} {}, 간격 {})", candles.size(), symbol, interval, Intervals.toDuration(interval));
    return candles;
  }
}
