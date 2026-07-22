package com.plaiground.dev;

import com.plaiground.domain.game.ninja.domain.Effect;
import com.plaiground.domain.game.ninja.domain.Gesture;
import com.plaiground.domain.game.ninja.domain.Skill;
import com.plaiground.domain.game.ninja.domain.SkillGesture;
import com.plaiground.domain.game.ninja.domain.SkillGestureId;
import com.plaiground.domain.game.ninja.repository.EffectRepository;
import com.plaiground.domain.game.ninja.repository.GestureRepository;
import com.plaiground.domain.game.ninja.repository.SkillGestureRepository;
import com.plaiground.domain.game.ninja.repository.SkillRepository;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 게임 콘텐츠(손동작/이펙트/스킬) 시드 데이터 — 아직 정식 관리 도구/마이그레이션이 없어서
// 앱 시작 시 비어있으면 채워 넣는다. hand-gesture-recognition-mediapipe/app.py의 SKILLS
// 프로토타입 데이터를 그대로 옮김. dev 패키지 밖(RoomRepository/인증)과 달리 이건 "가짜"가
// 아니라 진짜 게임 콘텐츠라, 나중에 정식 시드 스크립트로 옮기는 정도로 대체하면 됨.
@Component
public class DevNinjaDataSeeder implements ApplicationRunner {

    private final GestureRepository gestureRepository;
    private final EffectRepository effectRepository;
    private final SkillRepository skillRepository;
    private final SkillGestureRepository skillGestureRepository;

    public DevNinjaDataSeeder(
        GestureRepository gestureRepository,
        EffectRepository effectRepository,
        SkillRepository skillRepository,
        SkillGestureRepository skillGestureRepository
    ) {
        this.gestureRepository = gestureRepository;
        this.effectRepository = effectRepository;
        this.skillRepository = skillRepository;
        this.skillGestureRepository = skillGestureRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (skillRepository.count() > 0) {
            return;
        }

        Map<String, Gesture> gestures = Stream.of(
            gesture("snake", "뱀"), gesture("girl_V", "브이"), gesture("mouse", "쥐"),
            gesture("Horse", "말"), gesture("cow", "소"), gesture("rabbit", "토끼"),
            gesture("cat", "고양이"), gesture("spider", "거미"), gesture("sailor_moon", "세일러문")
        ).map(gestureRepository::save).collect(Collectors.toMap(Gesture::getName, g -> g));

        Effect effect = effectRepository.save(Effect.builder()
            .name("골드 버스트").color("#ffd700")
            .particleCount(28).life(20).radiusMin(2).radiusMax(5).speedMin(1).speedMax(3)
            .build());

        seedSkill(gestures, effect, "뇌절", 20, "Horse", "snake");
        seedSkill(gestures, effect, "봉선화의 술", 30, "snake", "mouse", "cow");
        seedSkill(gestures, effect, "수룡탄의 술", 30, "sailor_moon", "cow", "rabbit");
        seedSkill(gestures, effect, "냥냥펀치", 15, "girl_V", "cat");
        seedSkill(gestures, effect, "바람의 상처", 45, "spider", "Horse", "mouse", "sailor_moon");
    }

    private void seedSkill(Map<String, Gesture> gestures, Effect effect, String name, int damage, String... sequence) {
        Skill skill = skillRepository.save(Skill.builder().name(name).damage(damage).effect(effect).build());
        int seq = 1;
        for (String gestureName : sequence) {
            skillGestureRepository.save(SkillGesture.builder()
                .id(new SkillGestureId(skill.getId(), seq++))
                .skill(skill)
                .gesture(gestures.get(gestureName))
                .build());
        }
    }

    private static Gesture gesture(String name, String labelKr) {
        return Gesture.builder().name(name).labelKr(labelKr).build();
    }
}
