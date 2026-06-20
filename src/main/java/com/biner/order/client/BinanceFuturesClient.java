package com.biner.order.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.time.Instant;

@Slf4j
@Component
public class BinanceFuturesClient {

    private final WebClient webClient;

    @Value("${binance.api.key:dummy}")
    private String apiKey;

    @Value("${binance.api.secret:dummy}")
    private String apiSecret;

    @Value("${binance.api.url:https://testnet.binancefuture.com}")
    private String baseUrl;

    public BinanceFuturesClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    @RateLimiter(name = "binanceApi")
    @CircuitBreaker(name = "binanceApi", fallbackMethod = "orderFallback")
    @Retry(name = "binanceApi")
    public String placeOrder(String symbol, String side, String type, BigDecimal quantity, BigDecimal price) {
        long timestamp = Instant.now().toEpochMilli();
        
        String queryString = String.format("symbol=%s&side=%s&type=%s&quantity=%s&timestamp=%d", 
                symbol, side, type, quantity.toPlainString(), timestamp);
                
        if (price != null) {
            // STOP_MARKET은 stopPrice 파라미터를 사용해야 합니다.
            if ("STOP_MARKET".equals(type) || "TAKE_PROFIT_MARKET".equals(type)) {
                queryString += "&stopPrice=" + price.toPlainString();
            } else {
                queryString += "&price=" + price.toPlainString();
            }
        }

        String signature = generateSignature(queryString, apiSecret);
        queryString += "&signature=" + signature;

        log.info("Sending order to Binance: URL={}?{}", baseUrl + "/fapi/v1/order", queryString);

        try {
            return webClient.post()
                    .uri(baseUrl + "/fapi/v1/order?" + queryString)
                    .header("X-MBX-APIKEY", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to place order via WebClient", e);
            throw e;
        }
    }

    /**
     * [TODO 완료] 미체결 주문(스탑로스, 익절) 일괄 취소 API 호출 로직 추가
     */
    @RateLimiter(name = "binanceApi")
    @CircuitBreaker(name = "binanceApi", fallbackMethod = "cancelFallback")
    @Retry(name = "binanceApi")
    public String cancelAllOpenOrders(String symbol) {
        long timestamp = Instant.now().toEpochMilli();
        String queryString = String.format("symbol=%s&timestamp=%d", symbol, timestamp);
        String signature = generateSignature(queryString, apiSecret);
        queryString += "&signature=" + signature;

        log.info("Canceling all open orders for symbol: {}", symbol);

        try {
            return webClient.delete()
                    .uri(baseUrl + "/fapi/v1/allOpenOrders?" + queryString)
                    .header("X-MBX-APIKEY", apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to cancel open orders via WebClient", e);
            throw e;
        }
    }

    private String orderFallback(String symbol, String side, String type, BigDecimal quantity, BigDecimal price, Throwable t) {
        log.error("Order Execution Fallback Triggered. Symbol={}, Side={}. Reason: {}", symbol, side, t.getMessage());
        return "FALLBACK_TRIGGERED";
    }

    private String cancelFallback(String symbol, Throwable t) {
        log.error("Cancel Orders Fallback Triggered. Symbol={}. Reason: {}", symbol, t.getMessage());
        return "FALLBACK_TRIGGERED";
    }

    private String generateSignature(String data, String key) {
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256");
            sha256_HMAC.init(secret_key);
            byte[] bytes = sha256_HMAC.doFinal(data.getBytes("UTF-8"));
            
            StringBuilder hash = new StringBuilder();
            for (byte b : bytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hash.append('0');
                hash.append(hex);
            }
            return hash.toString();
        } catch (Exception e) {
            throw new RuntimeException("Unable to sign message", e);
        }
    }
}
