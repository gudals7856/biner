# 바이낸스 선물 자동 매매 시스템 (Biner)

## 1. 개요

이 프로젝트는 바이낸스 선물 시장(Binance Futures)에서 USDT 기반 무기한 계약 상품을 자동으로 거래하는 시스템입니다. `Ta4j` 라이브러리를 활용한 다양한 기술적 분석 지표를 기반으로, '추세 추종 + 눌림목 매수' 전략을 사용합니다.

특히, 시스템의 안정성과 리스크 관리에 중점을 두어 다음과 같은 핵심 기능을 구현했습니다.

- **체계적인 분할 매수 (DCA):** 예측이 빗나갔을 때 손실을 최소화하고 탈출 확률을 높입니다.
- **이중 안전망 (전략적 청산 + 하드 스탑):** 유연한 전략적 판단과 기계적인 최후의 안전장치를 모두 갖추었습니다.
- **실시간 상태 동기화:** 웹소켓을 통해 거래소에서 발생하는 모든 체결 정보를 실시간으로 수신하여, 시스템의 상태를 실제 계좌와 완벽하게 일치시킵니다.
- **유연한 설정 관리:** 모든 핵심 전략 파라미터는 `application.yml` 파일을 통해 관리되어, 코드 수정 없이 전략을 튜닝할 수 있습니다.

## 2. 핵심 매매 전략

> 아래 기능 중 일부는 `application.yml`의 `trading.strategy.*` 스위치로 켜고 끕니다. 현재 기본값은 다음과 같습니다.
>
> | 기능 | 설정 키 | 기본값 |
> |---|---|---|
> | 익절: 볼린저 상단 밖 → 복귀 | `bollinger-upper-exit-enabled` | `true` |
> | 익절: RSI 과매수 이탈 | `rsi-overbought-exit-enabled` | `false` |
> | 손절: MACD 데드크로스 / 기준선 이탈 | `strategic-stop-loss-enabled` | `false` |
> | 물타기(DCA) | `dca-enabled` | `false` |
>
> 하드 손절(-2%) / 익절(+4%) 보호 주문은 항상 동작합니다.

### 2.1. 신규 포지션 진입 (Entry)

신규 포지션 진입은 **'상승 추세 중 눌림목'**을 포착하는 것을 목표로 합니다.

- **필수 조건: 상승 추세 확인**
  - 일목균형표(Ichimoku Cloud)의 전환선(`Tenkan-sen`)이 기준선(`Kijun-sen`) 이상이고,
  - 종가가 200 이동평균선 위에 있어야 합니다. (장기 추세 필터)

- **선택 조건: 점수제 (3개 중 2개 이상 충족)**
  - 위 필수 조건이 만족된 상태에서, 아래 3가지 '눌림목' 또는 '반등 임박' 신호 중 **2개 이상**이 동시에 발생하면 신규 롱(LONG) 포지션에 진입합니다.
    1. **MACD 골든크로스:** 단기 추세가 상승으로 전환되는 신호
    2. **RSI 과매도:** 가격이 단기적으로 과도하게 하락했음을 의미하는 신호 (기본값: 45 이하)
    3. **볼린저 밴드 하단 터치:** 가격이 통계적으로 하단 지지선에 닿았음을 의미하는 신호

### 2.2. 물타기 (Dollar-Cost Averaging - DCA)

첫 진입 후 가격이 예상과 반대로 하락할 경우, 미리 정해진 규칙에 따라 추가 매수를 진행하여 평균 단가를 낮추고 탈출 확률을 높입니다.

- **DCA 실행 조건**
  1. 현재 포지션이 **최대 물타기 횟수(`dca.max-count`)**에 도달하지 않았어야 합니다.
  2. 현재 가격이 포지션의 **평균 단가 대비 특정 비율(`dca.dca-drop-percent`) 이상 하락**해야 합니다.
  3. 위 조건이 충족되었을 때, 신규 진입과 동일하게 **RSI 과매도** 및 **볼린저 밴드 하단 터치** 조건이 동시에 발생하여, 기술적으로도 반등 확률이 높은 안전한 지점이라고 판단될 때만 물타기를 실행합니다.

### 2.3. 포지션 청산 (Exit)

포지션 청산은 크게 **'수익 실현(Take-Profit)'**과 **'손실 제한(Stop-Loss)'** 두 가지 시나리오로 나뉩니다. 특히 손실 제한은 두 단계의 안전망으로 구성됩니다.

#### 2.3.1. 전략적 익절 (Strategic Take-Profit)

- **조건:** 포지션이 **수익 상태**일 때, 아래 조건 중 하나라도 충족되면 시스템이 능동적으로 익절 신호를 보냅니다.
  1. **RSI 과매수 이탈:** RSI가 70 이상이었다가 70 아래로 내려올 때
  2. **볼린저 밴드 상단 복귀:** 직전 종가가 상단 밖에 있다가 현재 종가가 상단 안으로 들어올 때

#### 2.3.2. 전략적 손절 (Strategic Stop-Loss)

- **조건:** 포지션이 **손실 상태**일 때, 아래 조건 중 하나라도 충족되면 하드 스탑에 도달하기 전에 시스템이 먼저 손절 신호를 보냅니다.
  1. **MACD 데드크로스:** 단기 추세가 하락으로 전환되는 신호
  2. **기준선 하향 이탈:** 가격이 일목균형표의 주요 지지선인 기준선(`Kijun-sen`) 아래로 마감될 때

#### 2.3.3. 하드 스탑 & 익절 (보호 주문)

- **역할:** 시스템의 상태나 전략적 판단과 관계없이, 계좌를 보호하는 **최후의 기계적 안전장치**입니다.
- **동작:** 진입/물타기 체결이 DB에 커밋된 직후, `OrderExecutionService`가 거래소에 두 개의 조건부 주문을 등록합니다.
  - **Stop-Loss:** 평균 단가 대비 `-X%` (`risk-management.stop-loss-percent`) 에 `STOP_MARKET`
  - **Take-Profit:** 평균 단가 대비 `+Y%` (`risk-management.take-profit-percent`) 에 `TAKE_PROFIT_MARKET`
  - 두 주문 모두 `closePosition=true`, `workingType=MARK_PRICE` 입니다. 포지션이 0이면 반대 포지션을 열지 않습니다.
  - 바이낸스 정책(2025-12~)에 따라 기본적으로 Algo Order API(`/fapi/v1/algoOrder`)로 전송합니다. (`binance.api.conditional-order-mode`)
- 바이낸스에는 선물 OCO가 없으므로, 한쪽이 체결되어 포지션이 청산되면 `PositionService`가 남은 주문을 즉시 일괄 취소합니다.
- **손절 주문 설정에 실패하면** 보호되지 않은 포지션을 남기지 않도록 즉시 시장가 청산합니다. (`risk-management.close-on-protection-failure`)
- 이 주문들은 거래소 서버에 등록되므로, 우리 시스템이 멈추거나 인터넷이 끊겨도 가격이 도달하면 체결됩니다.

#### 2.3.4. 비상 탈출 (Emergency Exit - 본전 탈출)

- **역할:** '전략이 실패했음'을 인정한 포지션에서 **손실을 최소화하며 탈출**하는 것을 최우선 목표로 하는 가장 중요한 위험 관리 전략입니다.
- **조건:** 물타기를 반복하여 **최대 물타기 횟수(`dca.max-count`)**에 도달한 포지션은 '집중 관리 대상'으로 전환됩니다.
- **동작:** 이 상태의 포지션은 더 이상 일반적인 익절/손절 로직을 따르지 않습니다. 대신, 가격이 반등하여 **자신의 평균 단가(본전) 이상으로 회복되는 순간**, 시스템은 더 큰 수익을 기다리지 않고 즉시 전량 매도하여 포지션을 정리합니다.

## 3. 시스템 아키텍처

이벤트 기반 아키텍처를 채택하여 각 서비스의 역할을 명확히 분리하고 안정성을 높였습니다.

- **`StrategyEngine` (두뇌):** 캔들 데이터를 분석하여 매매 신호(`TradeSignal`)를 발행합니다.
- **`PositionService` (매니저):** `TradeSignal`을 받아 주문 수량을 계산하고, 실제 주문을 위한 `PlaceOrderEvent`를 발행합니다. 또한, 체결 완료 후 DB 상태를 최종적으로 업데이트하는 책임을 가집니다.
- **`OrderExecutionService` (손과 발):** `PlaceOrderEvent`를 받아 실제 바이낸스 API를 호출하여 주문을 전송하고, OCO 주문을 설정하는 역할만 수행합니다.
- **`BinanceUserDataStreamListener` (귀):** 바이낸스 웹소켓에 연결하여 내 계정에서 발생하는 모든 주문 체결(내가 낸 주문, 보호 주문 등)을 실시간으로 감지하고, 이를 유일한 '진실의 원천'인 `OrderFilledEvent`로 발행합니다.

``` mermaid
graph TD
    subgraph "우리 시스템 (Application)"
        A["<b style='font-size:16px'>귀 (Ears)</b><br/>BinanceUserDataStreamListener"]
        B["<b style='font-size:16px'>두뇌 (Brain)</b><br/>StrategyEngine"]
        C["<b style='font-size:16px'>매니저 (Manager)</b><br/>PositionService"]
        D["<b style='font-size:16px'>손과 발 (Hands)</b><br/>OrderExecutionService"]
    end
subgraph "외부 (Binance)"
    E[<b style='font-size:16px'>API 서버</b><br/>주문, OCO 설정]
    F[<b style='font-size:16px'>WebSocket 서버</b><br/>실시간 체결 정보]
end

%% 데이터 흐름 정의
G[1. 캔들 데이터 수신] --> B
B -- "2. TradeSignal 발행<br/>(매수/매도/물타기 신호)" --> C
C -- "3. PlaceOrderEvent 발행<br/>(주문 수량 계산 완료)" --> D
D -- "4. REST API 호출<br/>(시장가 주문, OCO 주문 설정)" --> E
F -- "5. 실시간 체결 정보 수신<br/>(ORDER_TRADE_UPDATE)" --> A
A -- "6. OrderFilledEvent 발행<br/>(<b>단일 진실 공급원</b>)" --> C
C -- "7. DB 상태 업데이트 후<br/>PositionChangedEvent 발행" --> D

%% 스타일링
style A fill:#e6f3ff,stroke:#0066cc
style B fill:#e6f3ff,stroke:#0066cc
style C fill:#e6f3ff,stroke:#0066cc
style D fill:#e6f3ff,stroke:#0066cc
style E fill:#fff0e6,stroke:#ff6600
style F fill:#fff0e6,stroke:#ff6600
```
### 매매 전략 상세 흐름도

``` mermaid
graph TD
    A[캔들 마감] --> B{포지션 보유?};
    B -- NO --> C[<b>신규 진입</b>];
    B -- YES --> D[<b>기존 포지션 관리</b>];
    
subgraph "신규 진입 로직"
    C --> C1{"상승 추세인가?<br/>(전환선 >= 기준선)"};
    C1 -- NO --> X[무시];
    C1 -- YES --> C2{"눌림목 점수 >= 2?<br/>(MACD 골든크로스, RSI 과매도, BB 하단 터치)"};
    C2 -- NO --> X;
    C2 -- YES --> C3["✅ <b>신규 매수</b> 신호 발행"];
end

subgraph "기존 포지션 관리"
    D --> D1{"<b style='color:red'>비상 상황인가?</b><br/>(isMaxDcaReached == true)"};
    D1 -- YES --> D2{"<b style='color:green'>본전 탈출 가능한가?</b><br/>(현재가 >= 평단가)"};
    D1 -- NO --> D4{"수익 상태인가?<br/>(현재가 > 평단가)"};
    
    D2 -- YES --> D3["🚨 <b>전량 매도</b> 신호 발행<br/>(본전 탈출)"];
    D2 -- NO --> Y[대기];

    D4 -- YES --> D5{"전략적 익절 조건?<br/>(RSI 과매수 또는 BB 상단 터치)"};
    D4 -- NO --> D7{"전략적 손절 조건?<br/>(MACD 데드크로스 또는 기준선 이탈)"};

    D5 -- YES --> D6["💰 <b>전량 매도</b> 신호 발행<br/>(익절)"];
    D5 -- NO --> Y;
    
    D7 -- YES --> D6;
    D7 -- NO --> D8{"물타기 조건?<br/>(가격 하락 & RSI 과매도 & BB 하단 터치)"};

    D8 -- YES --> D9["💧 <b>추가 매수</b> 신호 발행<br/>(물타기)"];
    D8 -- NO --> Y;
end
```

### 3.1. 안전장치

| 위험 | 대응 |
|---|---|
| 체결 전 같은 신호가 다시 발생 → 중복 진입 | 주문 후 체결/실패 이벤트가 올 때까지 해당 심볼은 '주문 대기' 상태로 신호 무시 (`pending-order-timeout-seconds` 초과 시 알림 후 해제) |
| 네트워크 재시도로 같은 주문이 두 번 전송 | 모든 주문에 `newClientOrderId` 부여. 재시도는 네트워크 오류/5xx에만 수행, 중복 ID 응답(-4116)은 성공으로 처리 |
| 주문 실패가 조용히 묻힘 | fallback 없이 예외 전파 → `OrderFailedEvent` 발행 + 텔레그램 알림 |
| 청산 주문이 반대 포지션을 엶 | 전략적 청산은 `reduceOnly=true`, 보호 주문은 `closePosition=true` |
| 포지션이 없는데 늦게 도착한 청산 체결 / 수동 주문 | 우리 진입 주문(`bnE-` 접두사)이 아니면 새 포지션으로 기록하지 않고 알림 |
| 사용자 데이터 스트림 끊김 / Listen Key 만료 | 연결 종료까지 블로킹, 30분마다 keepalive, 끊기면 지수 백오프 재연결, orderId 기준 중복 체결 제거 |
| 시세 스트림 끊김 / 멈춤 | 15초마다 감시, 60초간 수신 없으면 누락 캔들을 REST로 보충 후 재연결 |
| 재시작 직후 지표 계산 불가 | 시작 시 과거 캔들을 REST로 불러와 지표 워밍업 (신호는 발생시키지 않음) |

## 4. 실행 방법

### 4.1. 환경 변수

`.env.example`을 `.env`로 복사한 뒤 값을 채웁니다. 비밀번호와 API 키는 코드나 설정 파일에 두지 않습니다.

```bash
cp .env.example .env
```

```bash
docker compose up -d
```

애플리케이션은 `.env`를 자동으로 읽지 않으므로, 셸에서 export 하거나 IDE 실행 설정에 등록합니다.

```bash
set -a && source .env && set +a
```

### 4.2. 백테스트

```bash
./gradlew bootRun --args='--spring.profiles.active=dataloader --dataloader.file=BTCUSDT_5m_since_2025-01-01.xlsx --dataloader.timeframe=5m'
```

```bash
./gradlew bootRun --args='--spring.profiles.active=backtest --backtest.timeframe=5m'
```

- `backtest.timeframe`은 적재한 데이터의 `dataloader.timeframe`과 같아야 합니다.
- 리포트의 MDD는 평가 자산(미실현 손익 포함) 기준이며, 승률은 수수료를 뺀 순손익 기준입니다.

### 4.3. 테스트

```bash
./gradlew test
```

### 4.4. 운영 전환 체크리스트

1. 테스트넷에서 진입 → 보호 주문 등록 → 손절/익절 체결 → 남은 주문 취소까지 한 사이클을 확인합니다.
2. `BINANCE_API_URL`, `BINANCE_MARKET_WS_URL`, `BINANCE_USER_WS_URL`을 운영 주소로 함께 바꿉니다.
3. `trading.general.price-tick-size` / `quantity-step-size`가 거래 심볼의 거래소 규칙과 맞는지 확인합니다.
