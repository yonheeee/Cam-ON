package com.camon.global.config;

import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

@Configuration
public class SchedulingConfig {

    // 라운드 타임아웃처럼 "n초 뒤에 한 번" 실행되는 게임 타이머용. 방 개수가 적어 풀 크기는 소박하게 잡음.
    @Bean
    TaskScheduler taskScheduler() {
        return new ConcurrentTaskScheduler(Executors.newScheduledThreadPool(4));
    }
}
