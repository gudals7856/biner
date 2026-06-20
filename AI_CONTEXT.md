# 프로젝트 개요 (Biner Project Context)
[cite_start]이 프로젝트는 개인 사용 목적으로 개발하는 바이낸스 선물(Futures) 자동 매매 봇입니다[cite: 20, 268]. 안정성과 무중단 운영을 최우선으로 합니다.

## 🛠 기술 스택
- [cite_start]**Language & Framework**: Java 21 이상, Spring Boot 3.5.x 이상 (가상 스레드 및 최신 기술 활용)[cite: 375, 376].
- [cite_start]**Database**: PostgreSQL + TimescaleDB (시계열 데이터, 포지션 보관), Redis (트래픽 제어, 임시 상태 관리)[cite: 77, 80].
- [cite_start]**Infrastructure**: Docker Compose (인프라 격리 및 하이브리드 로컬 개발)[cite: 238, 253].

## 🏗 아키텍처 설계 (Architecture Guidelines)
[cite_start]본 프로젝트는 단일 프로젝트 내에서 패키지로 도메인을 격리하는 **모듈러 모놀리스(Modular Monolith)** 아키텍처를 따릅니다[cite: 318]. [cite_start]도메인 간의 결합도를 낮추기 위해 스프링의 `ApplicationEventPublisher`를 활용한 이벤트 기반(Event-driven) 소통을 지향합니다[cite: 153].

### 핵심 도메인 분리 (Package-by-Feature)
코드를 제안할 때 반드시 아래의 도메인 역할에 맞게 클래스와 인터페이스를 배치하세요.
1. [cite_start]**`market` (마켓 데이터 도메인)**: 바이낸스 WebSocket/REST API에서 5분봉(kline_5m) 데이터를 수집하고, 정각마다 `CandleClosedEvent`를 발행하는 손상 방지 계층[cite: 271, 272, 440, 441].
2. [cite_start]**`account` (포지션 및 계좌 도메인)**: 지갑 잔고, 진입/청산 내역, 현재 포지션(Long/Short) 상태 및 수익률 캡슐화 보관[cite: 275, 276].
3. **`strategy` (전략 도메인)**: TA4J 라이브러리를 활용. [cite_start]`CandleClosedEvent`를 구독하여 일목균형표(추세), MACD(모멘텀), RSI(과매수/과매도), 볼린저 밴드(타점) 지표를 검사하고, 조건 만족 시 `TradeSignal` 발행[cite: 278, 279, 418, 421].
4. **`order` 또는 `trade` (주문 집행 도메인)**: 외부 거래소(Binance)와 실제 통신. [cite_start]반드시 진입 주문과 동시에 스탑로스(Stop Market)가 걸리도록 OCO(One Cancels the Other) 주문 로직을 방어적으로 구현[cite: 384, 389, 426].
5. [cite_start]**`notification` (알림 및 로깅 도메인)**: 시스템 오류 및 매매 체결 시 비동기(`@Async`)로 텔레그램/디스코드 알림 전송[cite: 284, 285].
6. [cite_start]**`backtest` (백테스팅 도메인)**: CSV나 DB의 과거 데이터를 읽어와 이벤트를 리플레이(Mocking)하며, 수수료와 슬리피지를 엄격히 차감하여 현실적인 수익률과 MDD를 검증[cite: 287, 288, 460].

## 🚨 코딩 규칙 및 주의사항 (Critical Rules)
- [cite_start]**보안 (No Hardcoding)**: 바이낸스 API 키(Key, Secret)는 절대 코드에 하드코딩하지 말고, 반드시 환경변수(`.env`)나 `application.yml`을 통해 주입받도록 구성하세요[cite: 164, 244].
- [cite_start]**안전장치 (Defensive Programming)**: 클라우드 장애나 API Rate Limit으로 인한 청산을 방지하기 위해 주문 로직은 극단적으로 방어적이어야 합니다 (재시도 로직, 멱등성 보장, OCO 필수)[cite: 37, 38, 424, 425].
- [cite_start]**수수료 및 슬리피지 고증**: 백테스팅 코드나 수익률 계산 로직을 작성할 때는 반드시 슬리피지와 수수료를 차감하는 로직을 기본으로 포함하세요[cite: 460].