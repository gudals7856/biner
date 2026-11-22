package com.biner.backtest;

import com.biner.backtest.engine.MockExecutionService;
import com.biner.backtest.engine.ReplayEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 'backtest' 프로필이 활성화되었을 때 백테스트를 자동으로 실행하는 컴포넌트입니다.
 * CommandLineRunner를 구현하여 애플리케이션 시작 시 run 메서드가 호출되도록 합니다.
 */
@Slf4j
@Component
@Profile("backtest") // 'backtest' 프로필일 때만 이 컴포넌트를 활성화합니다.
@RequiredArgsConstructor
public class BacktestRunner implements CommandLineRunner {

    private final ReplayEngine replayEngine;
    private final MockExecutionService mockExecutionService;

    // application.yml 파일에서 백테스트 설정을 주입받습니다.
    @Value("${backtest.symbol}")
    private String symbol;

    @Value("${backtest.timeframe}")
    private String timeframe;

    @Value("${backtest.start-time}")
    private String startTime;

    @Value("${backtest.end-time}")
    private String endTime;

    @Override
    public void run(String... args) throws Exception {
        log.info("==================================================");
        log.info("백테스트 모드로 애플리케이션을 시작합니다.");
        log.info("심볼: {}, 시간봉: {}, 시작: {}, 종료: {}", symbol, timeframe, startTime, endTime);
        log.info("==================================================");

        // 1. 백테스트 시작 전, 모의 체결 서비스를 초기화합니다.
        mockExecutionService.reset();

        // 2. 설정 파일의 시간 문자열을 Instant 객체로 변환합니다.
        Instant start = Instant.parse(startTime);
        Instant end = Instant.parse(endTime);

        // 3. ReplayEngine을 사용하여 백테스트를 실행합니다.
        replayEngine.runBacktest(symbol, timeframe, start, end, mockExecutionService::markToMarket);

        // 4. 모든 리플레이가 끝난 후, 최종 결과 리포트를 출력합니다.
        mockExecutionService.printReport();

        // 5. 백테스트 완료 후 애플리케이션을 종료합니다.
        System.exit(0);
    }
}