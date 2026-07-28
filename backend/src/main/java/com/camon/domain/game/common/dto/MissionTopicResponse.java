package com.camon.domain.game.common.dto;

import com.camon.domain.game.common.MissionTopic;

// 몸으로 말해요 주제 목록 한 칸. 방장이 코스를 짤 때 게임과 함께 주제까지 미리 고르므로,
// 코스 설정 화면이 이 목록을 드롭다운으로 띄운다.
public record MissionTopicResponse(
    Long topicId,
    String name,
    // 이 주제에 등록된 제시어 수. 라운드 수를 늘릴 때 제시어가 모자라 게임이 중간에
    // 터지는 걸 막으려면 프론트가 (인원 x 라운드) <= 이 값인지 미리 알아야 한다.
    int missionCount
) {

    public static MissionTopicResponse of(MissionTopic topic, int missionCount) {
        return new MissionTopicResponse(
            topic.getTopicId(),
            topic.getName(),
            missionCount
        );
    }
}
