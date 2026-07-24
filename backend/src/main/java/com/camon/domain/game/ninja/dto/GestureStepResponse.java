package com.camon.domain.game.ninja.dto;

import com.camon.domain.game.ninja.domain.SkillGesture;

// 콤보 한 단계. gestureName은 AI 분류기(keypoint_classifier_label.csv) 라벨과 동일한 문자열 —
// 프론트/AI가 이 순서대로 포즈가 들어오는지 매칭한다.
public record GestureStepResponse(
    int seq,
    String gestureName,
    String gestureLabelKr
) {
    public static GestureStepResponse from(SkillGesture skillGesture) {
        return new GestureStepResponse(
            skillGesture.getId().getSeq(),
            skillGesture.getGesture().getName(),
            skillGesture.getGesture().getLabelKr()
        );
    }
}
