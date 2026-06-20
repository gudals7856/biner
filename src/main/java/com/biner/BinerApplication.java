package com.biner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling // 봇의 주기적인 작업(예: 주기적 DB 백업, API 핑 테스트 등)을 위해 활성화
@EnableAsync      // 이벤트 비동기 처리(텔레그램 알림 등 메인 스레드 대기 방지)를 위해 활성화
@SpringBootApplication
public class BinerApplication {

  public static void main(String[] args) {
    SpringApplication.run(BinerApplication.class, args);
  }
}