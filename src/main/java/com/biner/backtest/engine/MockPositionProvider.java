package com.biner.backtest.engine;

import com.biner.order.domain.Position;
import com.biner.order.service.PositionProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 백테스트 환경에서 사용되는 PositionProvider 구현체입니다.
 * MockExecutionService의 메모리에서 가상 포지션 정보를 조회합니다.
 */
@Service
@Profile("backtest") // 'backtest' 프로필일 때만 활성화됩니다.
@RequiredArgsConstructor
public class MockPositionProvider implements PositionProvider {

    private final MockExecutionService mockExecutionService;

    @Override
    public Optional<Position> findOpenPosition(String symbol) {
        // 백테스트에서는 심볼이 항상 하나이므로, symbol 인자를 사용하지 않고 바로 현재 가상 포지션을 반환합니다.
        return mockExecutionService.getCurrentPosition();
    }
}