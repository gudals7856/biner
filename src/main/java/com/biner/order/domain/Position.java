package com.biner.order.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 데이터베이스의 'positions' 테이블과 매핑되는 엔티티 클래스입니다.
 * 사용자의 현재 보유 중이거나 과거에 보유했던 포지션(거래 내역) 정보를 저장합니다.
 */
@Entity
@Table(name = "positions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 스펙을 위한 기본 생성자. 외부에서 임의 생성하지 못하게 protected로 설정.
public class Position {

    // 자동 증가(Auto Increment)되는 기본 키(PK)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 거래 대상 코인 심볼 (예: "BTCUSDT")
    @Column(nullable = false, length = 20)
    private String symbol;

    // 포지션의 방향 (LONG: 공매수 / SHORT: 공매도 / NONE: 포지션 없음)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PositionSide side;

    // 포지션의 현재 상태 (OPEN: 현재 보유 중 / CLOSED: 청산 완료)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PositionStatus status;

    // 진입 가격 (물타기가 된 경우 평균 단가를 의미함)
    @Column(name = "entry_price", nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    // 포지션 수량 (코인의 개수)
    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal amount;

    // 적용된 레버리지 배수 (현재는 1배를 사용)
    @Column(nullable = false)
    private int leverage;

    // 물타기(DCA) 진입 횟수. 초기 진입 시 1이며, 물타기 할 때마다 증가합니다.
    @Column(name = "dca_count", nullable = false)
    private int dcaCount = 0;

    // 최대 물타기 횟수에 도달했는지 여부를 나타내는 플래그
    @Column(name = "is_max_dca_reached", nullable = false)
    private boolean isMaxDcaReached = false;

    // 포지션 최초 생성(진입) 시각
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // 포지션 청산(종료) 시각. 아직 OPEN 상태이면 null입니다.
    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * 포지션의 방향을 정의하는 열거형
     */
    public enum PositionSide {
        LONG,  // 가격 상승에 베팅 (공매수)
        SHORT, // 가격 하락에 베팅 (공매도)
        NONE   // 포지션 없음
    }

    /**
     * 포지션의 상태를 정의하는 열거형
     */
    public enum PositionStatus {
        OPEN,  // 현재 열려있는(보유 중인) 포지션
        CLOSED // 종료된(청산된) 포지션
    }

    /**
     * 새로운 포지션을 생성하기 위한 빌더 생성자입니다.
     * 생성 시 상태는 자동으로 OPEN으로, 생성 시간은 현재 시간으로 설정됩니다.
     */
    @Builder
    public Position(String symbol, PositionSide side, BigDecimal entryPrice, BigDecimal amount, int leverage, int dcaCount) {
        this.symbol = symbol;
        this.side = side;
        this.status = PositionStatus.OPEN; // 초기 상태는 OPEN
        this.entryPrice = entryPrice;
        this.amount = amount;
        this.leverage = leverage;
        this.dcaCount = dcaCount;
        this.createdAt = Instant.now(); // 생성 시간 기록
    }

    /**
     * 포지션을 강제로 종료(청산) 처리할 때 호출하는 메서드입니다.
     * 상태를 CLOSED로 변경하고, 종료 시간을 기록합니다.
     */
    public void close() {
        this.status = PositionStatus.CLOSED;
        this.closedAt = Instant.now();
    }
    
    /**
     * 물타기(DCA)를 통해 추가 매수를 진행했을 때 포지션 정보를 업데이트하는 메서드입니다.
     * 
     * @param newAvgPrice 새롭게 계산된 평균 단가
     * @param newTotalAmount 새롭게 계산된 총 보유 수량
     * @param newDcaCount 업데이트된 누적 물타기 횟수
     */
    public void updateDca(BigDecimal newAvgPrice, BigDecimal newTotalAmount, int newDcaCount, int maxDcaCount) {
        this.entryPrice = newAvgPrice;
        this.amount = newTotalAmount;
        this.dcaCount = newDcaCount;
        if (newDcaCount >= maxDcaCount) {
            this.isMaxDcaReached = true;
        }
    }
}