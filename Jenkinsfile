pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
        timestamps()
    }

    parameters {
        string(
            name: 'DEPLOY_DIR',
            defaultValue: '/opt/camon',
            description: 'Deployment directory on the Jenkins/EC2 host'
        )
        string(
            name: 'DEPLOY_DOMAIN',
            defaultValue: 'i15b110.p.ssafy.io',
            description: 'Public Cam-ON domain'
        )
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Backend CI') {
            when {
                expression {
                    (env.BRANCH_NAME == 'develop-backend'
                        || env.BRANCH_NAME == 'develop'
                        || env.BRANCH_NAME == 'main'
                        || env.BRANCH_NAME?.startsWith('feature/backend/')
                        || env.BRANCH_NAME?.startsWith('feat/backend/')
                        || env.BRANCH_NAME?.startsWith('feat/infra/'))
                }
            }
            steps {
                dir('backend') {
                    sh '''
                        set -eu
                        redis_id=$(docker run -d --rm \
                            -p 127.0.0.1::6379 \
                            redis:7.4-alpine)
                        trap 'docker rm -f "${redis_id}" >/dev/null 2>&1 || true' EXIT

                        until docker exec "${redis_id}" redis-cli ping \
                            | grep -q PONG; do
                            sleep 1
                        done

                        redis_port=$(docker port "${redis_id}" 6379/tcp | sed 's/.*://')
                        chmod +x gradlew
                        REDIS_TEST_HOST=127.0.0.1 \
                        REDIS_TEST_PORT="${redis_port}" \
                        SPRING_PROFILES_ACTIVE=test \
                        ./gradlew clean test bootJar --no-daemon
                    '''
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'backend/build/test-results/test/*.xml'
                }
            }
        }

        stage('Frontend CI') {
            when {
                expression {
                    (env.BRANCH_NAME == 'develop-frontend'
                        || env.BRANCH_NAME == 'develop'
                        || env.BRANCH_NAME == 'main'
                        || env.BRANCH_NAME?.startsWith('feature/frontend/')
                        || env.BRANCH_NAME?.startsWith('feat/frontend/')
                        || env.BRANCH_NAME?.startsWith('feat/infra/'))
                }
            }
            steps {
                dir('frontend') {
                    sh '''
                        set -eu
                        npm ci
                        npm run lint
                        npm run build
                    '''
                }
            }
        }

        stage('Prepare Images') {
            when {
                anyOf {
                    branch 'develop'
                    branch 'main'
                    expression {
                        env.BRANCH_NAME?.startsWith('feat/infra/')
                    }
                }
            }
            steps {
                script {
                    env.IMAGE_TAG = sh(
                        script: 'git rev-parse --short=12 HEAD',
                        returnStdout: true
                    ).trim()

                    env.BACKEND_IMAGE = "camon-backend:${env.IMAGE_TAG}"
                    env.FRONTEND_IMAGE = "camon-web:${env.IMAGE_TAG}"
                }

                sh '''
                    set -eu
                    docker build \
                        --tag "${BACKEND_IMAGE}" \
                        backend

                    # Vite는 VITE_* 값을 빌드 시점에 번들에 굽는다 — 런타임 env로는 못 바꾼다.
                    # 그래서 배포에 필요한 외부 주소는 여기서 --build-arg로 넣어야 하고,
                    # 배포 .env를 단일 출처로 삼는다(값을 두 곳에 적으면 갈라진다).
                    #
                    # main은 이 이미지를 실제로 배포하므로 빠진 값은 배포 전에 실패시킨다 —
                    # 프론트는 값이 비어도 빌드가 성공하고 그 기능만 죽기 때문에, 통과시키면
                    # 배포 후에야 드러난다.
                    read_deploy_env() {
                        key="$1"
                        value=""
                        if [ -f "${DEPLOY_DIR}/.env" ]; then
                            value=$(sed -n "s/^${key}=//p" \
                                "${DEPLOY_DIR}/.env" | tail -n 1)
                        fi
                        if [ -z "${value}" ]; then
                            if [ "${BRANCH_NAME:-}" = "main" ]; then
                                echo "${key} is missing in ${DEPLOY_DIR}/.env" >&2
                                return 1
                            fi
                            echo "WARNING: ${key} not found in ${DEPLOY_DIR}/.env — frontend image will be built without it." >&2
                        fi
                        printf '%s' "${value}"
                    }

                    # LiveKit: 백엔드가 토큰을 서명하는 프로젝트와 반드시 같아야 한다.
                    # 어긋나면 토큰 서명은 정상인데 연결만 거부돼서 원인을 찾기 어렵다.
                    livekit_url=$(read_deploy_env LIVEKIT_URL) || exit 1
                    # AI 서버(물건 가져오기 인식): Spring과 별개 호스트라 주소를 따로 준다.
                    # 반드시 https여야 한다 — 배포 사이트는 HTTPS라 http 주소로 호출하면
                    # 브라우저가 mixed content로 차단하고, 그러면 인식이 통째로 죽는다.
                    # 안 넘기면 번들이 http://<접속호스트>:8100 폴백을 쓰는데 그게 정확히 차단되는 형태다.
                    ai_base_url=$(read_deploy_env AI_BASE_URL) || exit 1

                    docker build \
                        --build-arg "VITE_API_BASE_URL=https://${DEPLOY_DOMAIN}" \
                        --build-arg "VITE_WS_BASE_URL=wss://${DEPLOY_DOMAIN}" \
                        --build-arg "VITE_LIVEKIT_URL=${livekit_url}" \
                        --build-arg "VITE_AI_BASE_URL=${ai_base_url}" \
                        --tag "${FRONTEND_IMAGE}" \
                        frontend
                    BACKEND_IMAGE="${BACKEND_IMAGE}" \
                    FRONTEND_IMAGE="${FRONTEND_IMAGE}" \
                    DOMAIN="${DEPLOY_DOMAIN}" \
                    docker compose \
                        --env-file deploy/.env.example \
                        -f deploy/docker-compose.prod.yml \
                        config --quiet
                '''
            }
        }

        stage('Deploy Production') {
            when {
                branch 'main'
            }
            steps {
                sh '''
                    set -eu
                    test -f "${DEPLOY_DIR}/.env"
                    test -f "${DEPLOY_DIR}/secrets/jwt-private.pem"
                    test -f "${DEPLOY_DIR}/secrets/jwt-public.pem"

                    install -m 0644 \
                        deploy/docker-compose.prod.yml \
                        "${DEPLOY_DIR}/docker-compose.prod.yml"
                    install -m 0644 \
                        deploy/Caddyfile \
                        "${DEPLOY_DIR}/Caddyfile"
                    install -m 0755 \
                        deploy/deploy.sh \
                        "${DEPLOY_DIR}/deploy.sh"

                    BACKEND_IMAGE="${BACKEND_IMAGE}" \
                    FRONTEND_IMAGE="${FRONTEND_IMAGE}" \
                    DOMAIN="${DEPLOY_DOMAIN}" \
                    "${DEPLOY_DIR}/deploy.sh"
                '''
            }
        }
    }

    post {
        success {
            script {
                if (env.BRANCH_NAME == 'main') {
                    mattermostSend(
                        color: 'good',
                        message: "✅ **Cam-ON 운영 배포 성공**\n- 브랜치: `${env.BRANCH_NAME}`\n- 빌드: #${env.BUILD_NUMBER}\n- 확인: ${env.BUILD_URL}"
                    )
                }
            }
        }

        failure {
            script {
                if (env.BRANCH_NAME == 'develop') {
                    mattermostSend(
                        color: 'danger',
                        message: "❌ **develop 통합 빌드 실패**\n- 빌드: #${env.BUILD_NUMBER}\n- 로그: ${env.BUILD_URL}"
                    )
                } else if (env.BRANCH_NAME == 'main') {
                    mattermostSend(
                        color: 'danger',
                        text: '@here',
                        message: "🚨 **Cam-ON 운영 배포 실패**\n- 브랜치: `${env.BRANCH_NAME}`\n- 빌드: #${env.BUILD_NUMBER}\n- 로그: ${env.BUILD_URL}"
                    )
                }
            }
        }

        always {
            deleteDir()
        }
    }
}
