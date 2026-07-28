# GitLab CI/CD 배포 가이드 (EC2 + GitLab Runner)

develop-backend 브랜치에 push/머지되면 EC2에 자동 배포된다. 파이프라인 정의는 레포 루트
`.gitlab-ci.yml`. 배포 방식은 **EC2에 설치한 GitLab Runner(shell executor)가 서버에서 직접
`git pull` + `docker compose`를 실행**하는 구조라, CI가 SSH 키(.pem)를 쓰지 않는다.
(.pem은 사람이 서버에 SSH로 들어갈 때만 쓴다.)

## 배포가 실제로 도는 조건 (3개 다 충족돼야 함)

1. `.gitlab-ci.yml`이 develop-backend에 존재 (이 커밋으로 충족)
2. EC2에 러너가 `deploy` 태그로 등록되어 있고 ubuntu 사용자로 실행됨 (아래 1회 설정)
3. develop-backend에 push/머지 발생 = 트리거
   - 이미 머지가 끝난 뒤 러너를 설치했다면 새 push가 없어 자동으로 안 돈다 →
     GitLab → CI/CD → Pipelines → **Run pipeline**(branch: develop-backend)로 최초 1회 수동 실행.

## 러너 설치·등록 (EC2에서 1회, ubuntu 사용자 기준)

전제: EC2에 SSH 접속 가능해야 함(`.pem` 필요 + 보안그룹 22번 포트에 본인 IP 허용). Docker는
이미 설치돼 있고 ubuntu가 docker 그룹 소속, `~/camon`에 레포 clone + `deploy/.env` 준비 완료 상태.

```bash
# 1) gitlab-runner 설치 (Debian/Ubuntu)
curl -L "https://packages.gitlab.com/install/repositories/runner/gitlab-runner/script.deb.sh" | sudo bash
sudo apt-get install -y gitlab-runner

# 2) 러너가 ubuntu 사용자로 잡을 실행하도록 재설치 (docker 접근 + ~/camon + git 자격증명 상속)
sudo gitlab-runner uninstall
sudo gitlab-runner install --user ubuntu --working-directory /home/ubuntu
sudo systemctl restart gitlab-runner

# 3) 프로젝트에 러너 등록
#    URL/토큰: GitLab 프로젝트 → Settings → CI/CD → Runners 에서 확인
sudo gitlab-runner register \
  --non-interactive \
  --url "https://lab.ssafy.com/" \
  --registration-token "<프로젝트 registration token>" \
  --executor "shell" \
  --description "camon-ec2-deployer" \
  --tag-list "deploy" \
  --run-untagged="false" \
  --locked="true"
```

> 최신 GitLab은 registration-token 대신 "runner authentication token" 방식을 권장한다.
> 그 경우 Settings → CI/CD → Runners → "New project runner"로 러너를 먼저 만들고 나오는
> `glrt-...` 토큰으로 `gitlab-runner register --token "glrt-..."` 하면 된다(태그 `deploy`는
> 러너 생성 화면에서 지정).

## 확인

```bash
# 러너가 ubuntu로 돌고 docker 접근 되는지
sudo systemctl status gitlab-runner
sudo -u ubuntu docker ps

# 등록 상태는 GitLab → Settings → CI/CD → Runners 에서 초록불인지 확인
```

이후 develop-backend에 머지/push → 자동으로 `~/camon`에서 최신 코드 pull + 재빌드·기동된다.
파이프라인 로그는 GitLab → CI/CD → Pipelines 에서 확인.

## 주의

- 러너 설치 후 EC2 `~/camon`은 CI가 `git reset --hard origin/develop-backend`로 관리하므로,
  서버에서 직접 그 디렉토리를 수정하지 말 것(다음 배포 때 덮어써짐). `deploy/.env`는 gitignore라 유지된다.
- 현재 EC2 `~/camon`은 game/ninja-flow가 체크아웃돼 있는데, 첫 자동배포 때 develop-backend로 전환된다.
