package com.plaiground.domain.game.ninja.dto;

import com.plaiground.domain.game.ninja.domain.Skill;
import java.util.List;

// 다음 라운드에 나올 스킬 예고 — skill_order가 세션 시작 시 미리 셔플/고정돼 있어서 다음 라운드
// 것도 미리 알 수 있다. 지금 발동시킬 정보가 아니라 미리보기용이라 effect(파티클 연출)는 안 싣는다.
public record NextSkillPreview(
    Long skillId,
    String skillName,
    List<GestureStepResponse> gestures
) {
    public static NextSkillPreview of(Skill skill) {
        return new NextSkillPreview(
            skill.getId(),
            skill.getName(),
            skill.getGestures().stream().map(GestureStepResponse::from).toList()
        );
    }
}
