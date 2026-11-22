package com.biner.order.client;

import com.biner.order.client.dto.AccountBalanceResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 바이낸스 USDⓈ-M 선물 API와 직접 통신하는 클라이언트입니다.
 *
 * <ul>
 *   <li>모든 서명 요청은 {@link #signedRequest}를 통해 공통 처리합니다 (timestamp, recvWindow, HMAC 서명).</li>
 *   <li>주문 API에는 fallback을 두지 않습니다. 실패는 반드시 예외로 호출자에게 전파되어야 합니다.</li>
 *   <li>재시도(@Retry)는 네트워크 오류/5xx에만 적용되며, 시장가 주문은 newClientOrderId로 멱등성을 보장합니다.</li>
 * </ul>
 */
@Slf4j
@Component
public class BinanceFuturesClient {

  /** 조건부 주문(손절/익절) 전송 방식 */
  public enum ConditionalOrderMode {
    /** 2025-12 이후 바이낸스 정책: POST /fapi/v1/algoOrder 사용 */
    ALGO,
    /** 구 방식: POST /fapi/v1/order 에 STOP_MARKET/TAKE_PROFIT_MARKET 전송 */
    LEGACY
  }

  private static final String RESILIENCE_NAME = "binanceApi";
  private static final String ORDER_RESILIENCE_NAME = "binanceOrderApi";

  private final WebClient webClient;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Value("${binance.api.key}")
  private String apiKey;

  @Value("${binance.api.secret}")
  private String apiSecret;

  @Value("${binance.api.url:https://testnet.binancefuture.com}")
  private String baseUrl;

  @Value("${binance.api.recv-window:5000}")
  private long recvWindow;

  @Value("${binance.api.conditional-order-mode:ALGO}")
  private ConditionalOrderMode conditionalOrderMode;

  @Value("${binance.ws.user-stream-url:wss://stream.binancefuture.com/ws}")
  private String userStreamBaseUrl;

  public BinanceFuturesClient(WebClient.Builder webClientBuilder) {
    this.webClient = webClientBuilder.build();
  }

  // =================================================================
  // 계좌 / 설정
  // =================================================================

  @RateLimiter(name = RESILIENCE_NAME)
  @CircuitBreaker(name = RESILIENCE_NAME)
  @Retry(name = RESILIENCE_NAME)
  public String setLeverage(String symbol, int leverage) {
    log.info("레버리지 설정 요청: {} {}x", symbol, leverage);
    Map<String, String> params = new LinkedHashMap<>();
    params.put("symbol", symbol);
    params.put("leverage", String.valueOf(leverage));
    return signedRequest(HttpMethod.POST, "/fapi/v1/leverage", params, String.class);
  }

  @RateLimiter(name = RESILIENCE_NAME)
  @CircuitBreaker(name = RESILIENCE_NAME)
  @Retry(name = RESILIENCE_NAME)
  public AccountBalanceResponse getAccountBalance() {
    log.debug("바이낸스 계좌 정보 조회 요청...");
    return signedRequest(HttpMethod.GET, "/fapi/v2/account", new LinkedHashMap<>(), AccountBalanceResponse.class);
  }

  // =================================================================
  // 주문
  // =================================================================

  /**
   * 일반 주문(시장가/지정가)을 전송합니다.
   *
   * @param clientOrderId 멱등성 키. 재시도 시에도 같은 값이 전송되어 중복 주문이 거래소에서 거부됩니다.
   */
  @RateLimiter(name = RESILIENCE_NAME)
  @CircuitBreaker(name = RESILIENCE_NAME)
  @Retry(name = ORDER_RESILIENCE_NAME)
  public String placeOrder(String symbol, String side, String type, BigDecimal quantity, BigDecimal price,
                           boolean reduceOnly, String clientOrderId) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("symbol", symbol);
    params.put("side", side);
    params.put("type", type);
    params.put("quantity", quantity.toPlainString());
    if (price != null) {
      params.put("price", price.toPlainString());
      params.put("timeInForce", "GTC");
    }
    if (reduceOnly) {
      params.put("reduceOnly", "true");
    }
    params.put("newClientOrderId", clientOrderId);

    log.info("바이낸스 주문 전송: {} {} {} qty={} reduceOnly={} clientOrderId={}",
      symbol, side, type, quantity.toPlainString(), reduceOnly, clientOrderId);
    return signedRequest(HttpMethod.POST, "/fapi/v1/order", params, String.class);
  }

  /**
   * 전체 포지션을 청산하는 조건부 주문(STOP_MARKET / TAKE_PROFIT_MARKET)을 전송합니다.
   * closePosition=true 이므로 수량을 지정하지 않으며, 포지션이 없으면 반대 포지션을 열지 않습니다.
   * 트리거 기준은 MARK_PRICE를 사용하여 순간적인 체결가 꼬리(wick)에 의한 오발동을 줄입니다.
   *
   * @param type          "STOP_MARKET" 또는 "TAKE_PROFIT_MARKET"
   * @param triggerPrice  발동 가격
   * @param clientOrderId 클라이언트 주문 ID
   */
  @RateLimiter(name = RESILIENCE_NAME)
  @CircuitBreaker(name = RESILIENCE_NAME)
  @Retry(name = ORDER_RESILIENCE_NAME)
  public String placeClosePositionOrder(String symbol, String side, String type, BigDecimal triggerPrice,
                                        String clientOrderId) {
    Map<String, String> params = new LinkedHashMap<>();
    log.info("바이낸스 보호 주문 전송({}): {} {} {} trigger={} clientId={}",
      conditionalOrderMode, symbol, side, type, triggerPrice.toPlainString(), clientOrderId);

    if (conditionalOrderMode == ConditionalOrderMode.ALGO) {
      params.put("algoType", "CONDITIONAL");
      params.put("symbol", symbol);
      params.put("side", side);
      params.put("type", type);
      params.put("triggerPrice", triggerPrice.toPlainString());
      params.put("closePosition", "true");
      params.put("workingType", "MARK_PRICE");
      params.put("priceProtect", "true");
      params.put("clientAlgoId", clientOrderId);
      return signedRequest(HttpMethod.POST, "/fapi/v1/algoOrder", params, String.class);
    }

    params.put("symbol", symbol);
    params.put("side", side);
    params.put("type", type);
    params.put("stopPrice", triggerPrice.toPlainString());
    params.put("closePosition", "true");
    params.put("workingType", "MARK_PRICE");
    params.put("priceProtect", "true");
    params.put("newClientOrderId", clientOrderId);
    return signedRequest(HttpMethod.POST, "/fapi/v1/order", params, String.class);
  }

  /**
   * 심볼의 모든 대기 주문(일반 주문 + 조건부 Algo 주문)을 취소합니다.
   * 일반 주문 취소가 실패하면 예외를 던집니다. Algo 주문 취소는 LEGACY 모드에서는 생략합니다.
   */
  @RateLimiter(name = RESILIENCE_NAME)
  @CircuitBreaker(name = RESILIENCE_NAME)
  @Retry(name = RESILIENCE_NAME)
  public String cancelAllOpenOrders(String symbol) {
    log.info("심볼에 대한 모든 대기 주문 취소: {}", symbol);
    Map<String, String> params = new LinkedHashMap<>();
    params.put("symbol", symbol);
    String response = signedRequest(HttpMethod.DELETE, "/fapi/v1/allOpenOrders", params, String.class);

    if (conditionalOrderMode == ConditionalOrderMode.ALGO) {
      Map<String, String> algoParams = new LinkedHashMap<>();
      algoParams.put("symbol", symbol);
      String algoResponse = signedRequest(HttpMethod.DELETE, "/fapi/v1/algoOpenOrders", algoParams, String.class);
      response = response + " | algo: " + algoResponse;
    }
    return response;
  }

  // =================================================================
  // 사용자 데이터 스트림
  // =================================================================

  @RateLimiter(name = RESILIENCE_NAME)
  @CircuitBreaker(name = RESILIENCE_NAME)
  @Retry(name = RESILIENCE_NAME)
  public String getListenKey() {
    log.info("Listen Key 발급 요청...");
    String response = apiKeyRequest(HttpMethod.POST, "/fapi/v1/listenKey");
    try {
      return objectMapper.readTree(response).get("listenKey").asText();
    } catch (Exception e) {
      throw new IllegalStateException("Listen Key 파싱 실패", e);
    }
  }

  /**
   * Listen Key의 유효기간(60분)을 연장합니다. 30분 이내 주기로 호출해야 합니다.
   */
  @RateLimiter(name = RESILIENCE_NAME)
  @CircuitBreaker(name = RESILIENCE_NAME)
  @Retry(name = RESILIENCE_NAME)
  public void keepAliveListenKey() {
    apiKeyRequest(HttpMethod.PUT, "/fapi/v1/listenKey");
    log.debug("Listen Key 유효기간 연장 완료");
  }

  /**
   * 사용자 데이터 스트림 웹소켓에 연결합니다.
   * 반환된 연결 객체의 {@code closed} Future는 연결이 끊기면(정상 종료/오류) 완료됩니다.
   * 호출자는 이 Future를 기다려 연결이 끊길 때까지 블로킹할 수 있습니다.
   */
  public UserDataStreamConnection connectToUserDataStream(String listenKey, Consumer<String> messageConsumer) {
    String webSocketUrl = userStreamBaseUrl + "/" + listenKey;
    log.info("사용자 데이터 웹소켓에 연결합니다: {}/****", userStreamBaseUrl);

    CompletableFuture<Void> closed = new CompletableFuture<>();
    WebSocket webSocket = HttpClient.newHttpClient()
      .newWebSocketBuilder()
      .buildAsync(URI.create(webSocketUrl), new WebSocket.Listener() {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
          log.info("사용자 데이터 웹소켓 연결 성공");
          ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
          // 큰 메시지는 여러 프레임으로 나뉘어 올 수 있으므로 last=true일 때까지 누적합니다.
          buffer.append(data);
          if (last) {
            String message = buffer.toString();
            buffer.setLength(0);
            try {
              messageConsumer.accept(message);
            } catch (Exception e) {
              log.error("사용자 데이터 메시지 처리 중 오류", e);
            }
          }
          ws.request(1);
          return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
          log.warn("사용자 데이터 웹소켓 종료. 상태코드: {}, 원인: {}", statusCode, reason);
          closed.complete(null);
          return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
          log.error("사용자 데이터 웹소켓 오류 발생", error);
          closed.completeExceptionally(error);
        }
      })
      .join();

    return new UserDataStreamConnection(webSocket, closed);
  }

  /**
   * 사용자 데이터 스트림 연결 핸들.
   *
   * @param webSocket 웹소켓 객체 (종료 시 사용)
   * @param closed    연결 종료 시 완료되는 Future
   */
  public record UserDataStreamConnection(WebSocket webSocket, CompletableFuture<Void> closed) {
    public void close() {
      try {
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
      } catch (Exception ignored) {
        webSocket.abort();
      }
    }
  }

  // =================================================================
  // 내부 공통
  // =================================================================

  private <T> T signedRequest(HttpMethod method, String path, Map<String, String> params, Class<T> responseType) {
    params.put("recvWindow", String.valueOf(recvWindow));
    params.put("timestamp", String.valueOf(Instant.now().toEpochMilli()));
    String query = params.entrySet().stream()
      .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
      .collect(Collectors.joining("&"));
    String signedQuery = query + "&signature=" + sign(query);

    return webClient.method(method)
      .uri(URI.create(baseUrl + path + "?" + signedQuery))
      .header("X-MBX-APIKEY", apiKey)
      .retrieve()
      .bodyToMono(responseType)
      .block();
  }

  private String apiKeyRequest(HttpMethod method, String path) {
    return webClient.method(method)
      .uri(URI.create(baseUrl + path))
      .header("X-MBX-APIKEY", apiKey)
      .retrieve()
      .bodyToMono(String.class)
      .block();
  }

  private String sign(String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
      StringBuilder hash = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        hash.append(String.format("%02x", b));
      }
      return hash.toString();
    } catch (Exception e) {
      throw new IllegalStateException("메시지 서명 불가", e);
    }
  }
}
