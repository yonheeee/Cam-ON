package com.camon.dev;

import com.camon.domain.game.ninja.domain.Effect;
import com.camon.domain.game.ninja.domain.Gesture;
import com.camon.domain.game.ninja.domain.Skill;
import com.camon.domain.game.ninja.domain.SkillGesture;
import com.camon.domain.game.ninja.domain.SkillGestureId;
import com.camon.domain.game.ninja.repository.EffectRepository;
import com.camon.domain.game.ninja.repository.GestureRepository;
import com.camon.domain.game.ninja.repository.SkillGestureRepository;
import com.camon.domain.game.ninja.repository.SkillRepository;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 게임 콘텐츠(손동작/이펙트/스킬) 시드 데이터 — 아직 정식 관리 도구/마이그레이션이 없어서
// 앱 시작 시 없는 것만 채워 넣는다. hand-gesture-recognition-mediapipe/app.py의 SKILLS
// 프로토타입 데이터를 그대로 옮김. dev 패키지 밖(RoomRepository/인증)과 달리 이건 "가짜"가
// 아니라 진짜 게임 콘텐츠라, 나중에 정식 시드 스크립트로 옮기는 정도로 대체하면 됨.
//
// 항목 단위로 멱등하다(DevGameCatalogSeeder와 같은 방식). 예전엔 skill 테이블이 비어있을
// 때만 통째로 돌려서, 스킬을 새로 추가해도 이미 데이터가 있는 DB에는 영원히 들어가지 않았다.
@Slf4j
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
        Map<String, Gesture> gestures = seedGestures();
        Effect effect = seedEffect();

        seedSkill(gestures, effect, "뇌절", 20, "Horse", "snake");
        seedSkill(gestures, effect, "봉선화의 술", 30, "snake", "mouse", "cow");
        seedSkill(gestures, effect, "수룡탄의 술", 30, "sailor_moon", "cow", "rabbit");
        seedSkill(gestures, effect, "냥냥펀치", 15, "rabbit", "cat");
        seedSkill(gestures, effect, "바람의 상처", 45, "spider", "Horse", "mouse", "sailor_moon");
        // 테스트용 — 콤보가 2개뿐이고 데미지가 INITIAL_HP(100)와 같아서 한 방에 탈락시킨다.
        // 닌자 판(round)은 "최후 1인"이 남아야 끝나는데, 아무도 탈락하지 않으면 교환 상한(50회 x 30초)에
        // 닿을 때까지 25분씩 걸려 진행 확인이 사실상 불가능하다. 이 스킬로 판을 즉시 끝낼 수 있다.
        seedSkill(gestures, effect, "아마테라스", 100, "Horse", "mouse");
        // 콤보 4개짜리 고데미지 — 바람의 상처(45)와 같은 길이지만 손동작 구성이 겹치지 않게 잡았다.
        seedSkill(gestures, effect, "나선환", 50, "cat", "girl_V", "rabbit", "snake");
    }

    // 손동작은 name이 곧 분류기 라벨과의 문자열 계약이라 그 값으로 존재 여부를 본다.
    private Map<String, Gesture> seedGestures() {
        Map<String, Gesture> existing = gestureRepository.findAll().stream()
            .collect(Collectors.toMap(Gesture::getName, g -> g));
        Stream.of(
            gesture("snake", "뱀"), gesture("girl_V", "브이"), gesture("mouse", "쥐"),
            gesture("Horse", "말"), gesture("cow", "소"), gesture("rabbit", "토끼"),
            gesture("cat", "고양이"), gesture("spider", "거미"), gesture("sailor_moon", "세일러문")
        ).filter(g -> !existing.containsKey(g.getName()))
            .forEach(g -> {
                log.info("[Seed] gesture : '{}' 없음 → 추가", g.getName());
                existing.put(g.getName(), gestureRepository.save(g));
            });
        return existing;
    }

    // 지금은 이펙트가 한 종류뿐이라(스킬별 연출은 프론트의 Pixel*Effect가 담당) 첫 행을 재사용한다.
    private Effect seedEffect() {
        return effectRepository.findAll().stream().findFirst().orElseGet(() ->
            effectRepository.save(Effect.builder()
                .name("골드 버스트").color("#ffd700")
                .particleCount(28).life(20).radiusMin(2).radiusMax(5).speedMin(1).speedMax(3)
                .build())
        );
    }

    // 스킬 단위로 멱등하다 — 예전엔 skill 테이블이 비어있을 때만 통째로 돌려서, 스킬을 새로
    // 추가해도 이미 데이터가 있는 DB(로컬/배포)엔 영원히 들어가지 않았다. 이름이 같은 스킬이
    // 있으면 데미지/콤보를 덮어쓰지 않고 그대로 둔다(운영에서 밸런스를 조정해 뒀을 수 있다).
    private void seedSkill(Map<String, Gesture> gestures, Effect effect, String name, int damage, String... sequence) {
        if (skillRepository.findByName(name).isPresent()) {
            return;
        }
        log.info("[Seed] skill : '{}' 없음 → 추가 (damage={}, 콤보 {}개)", name, damage, sequence.length);
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
