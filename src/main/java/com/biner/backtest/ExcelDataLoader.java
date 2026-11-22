package com.biner.backtest;

import com.biner.common.util.Intervals;
import com.biner.market.domain.Candle;
import com.biner.market.domain.CandleId;
import com.biner.market.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 엑셀 파일로부터 캔들 데이터를 읽어와 DB에 저장하는 데이터 로더입니다.
 * 'dataloader' 프로필이 활성화되었을 때만 실행됩니다.
 */
@Slf4j
@Component
@Profile("dataloader")
@RequiredArgsConstructor
public class ExcelDataLoader implements CommandLineRunner {

    private final CandleRepository candleRepository;

    // 처리할 엑셀 파일의 경로와 기본 정보 (실행 인자 --dataloader.file=... 등으로 변경 가능)
    @Value("${dataloader.file:BTCUSDT_5m_since_2025-01-01.xlsx}")
    private String filePath;

    @Value("${dataloader.symbol:BTCUSDT}")
    private String symbol;

    @Value("${dataloader.timeframe:5m}")
    private String timeframe;

    @Override
    public void run(String... args) throws Exception {
        log.info("==================================================");
        log.info("엑셀 데이터 로더를 시작합니다. 파일: {}", filePath);
        log.info("==================================================");

        Duration candleDuration = Intervals.toDuration(timeframe);
        List<Candle> candles = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        ZoneId kstZone = ZoneId.of("Asia/Seoul");
        DataFormatter dataFormatter = new DataFormatter(); // DataFormatter 인스턴스 생성

        try (FileInputStream file = new FileInputStream(filePath);
             Workbook workbook = new XSSFWorkbook(file)) {

            Sheet sheet = workbook.getSheetAt(0);
            // 첫 번째 행(헤더)은 건너뜁니다.
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                try {
                    // 1. 엑셀에서 데이터 읽기 (DataFormatter 사용)
                    String dateKstStr = dataFormatter.formatCellValue(row.getCell(0));
                    String openStr = dataFormatter.formatCellValue(row.getCell(1));
                    String highStr = dataFormatter.formatCellValue(row.getCell(2));
                    String lowStr = dataFormatter.formatCellValue(row.getCell(3));
                    String closeStr = dataFormatter.formatCellValue(row.getCell(4));
                    String volumeStr = dataFormatter.formatCellValue(row.getCell(5));

                    BigDecimal open = new BigDecimal(openStr);
                    BigDecimal high = new BigDecimal(highStr);
                    BigDecimal low = new BigDecimal(lowStr);
                    BigDecimal close = new BigDecimal(closeStr);
                    BigDecimal volume = new BigDecimal(volumeStr);

                    // 2. 데이터 변환 및 보강
                    LocalDateTime ldtKst = LocalDateTime.parse(dateKstStr, formatter);
                    ZonedDateTime zdtKst = ldtKst.atZone(kstZone);
                    Instant openTime = zdtKst.toInstant();
                    // 바이낸스 규칙과 동일하게 closeTime = openTime + 간격 - 1ms
                    Instant closeTime = openTime.plus(candleDuration).minusMillis(1);

                    // 3. 복합 키 객체 생성
                    CandleId candleId = new CandleId(openTime, symbol, timeframe);

                    // 4. Candle 엔티티 생성
                    Candle candle = Candle.builder()
                            .id(candleId) // 복합 키 객체를 전달
                            .closeTime(closeTime)
                            .open(open)
                            .high(high)
                            .low(low)
                            .close(close)
                            .volume(volume)
                            .build();

                    candles.add(candle);

                } catch (Exception e) {
                    log.error("엑셀 {}번 행 처리 중 오류 발생. 이 행은 건너뜁니다.", i + 1, e);
                }
            }
        }

        if (!candles.isEmpty()) {
            log.info("{}개의 캔들 데이터를 DB에 저장합니다...", candles.size());
            candleRepository.saveAll(candles);
            log.info("데이터 저장이 완료되었습니다.");
        } else {
            log.warn("엑셀 파일에서 처리할 데이터를 찾지 못했습니다.");
        }

        log.info("==================================================");
        log.info("엑셀 데이터 로더 작업을 종료합니다.");
        log.info("==================================================");

        // 작업 완료 후 애플리케이션 자동 종료
        System.exit(0);
    }
}