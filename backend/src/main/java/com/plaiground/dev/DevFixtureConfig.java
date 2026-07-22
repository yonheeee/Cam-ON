package com.plaiground.dev;

import com.plaiground.domain.room.repository.RoomRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// TEMP — dev 패키지의 스텁 빈 등록 지점. @ConditionalOnMissingBean이라 방/코스 도메인에
// 진짜 RoomRepository 구현체가 생기면 이 빈은 자동으로 안 뜬다(그때 이 클래스와 dev 패키지
// 전체를 지우면 됨).
@Configuration
public class DevFixtureConfig {

    @Bean
    @ConditionalOnMissingBean(RoomRepository.class)
    RoomRepository devRoomRepository() {
        return new DevRoomRepository();
    }
}
