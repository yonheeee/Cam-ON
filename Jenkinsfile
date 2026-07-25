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
                    docker build \
                        --build-arg "VITE_API_BASE_URL=https://${DEPLOY_DOMAIN}" \
                        --build-arg "VITE_WS_BASE_URL=wss://${DEPLOY_DOMAIN}" \
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
        always {
            deleteDir()
        }
    }
}
