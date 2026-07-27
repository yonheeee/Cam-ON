#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.prod.yml"
ENV_FILE="${SCRIPT_DIR}/.env"
STATE_FILE="${SCRIPT_DIR}/.deployed-images"
HEALTH_ATTEMPTS="${HEALTH_ATTEMPTS:-30}"
HEALTH_INTERVAL_SECONDS="${HEALTH_INTERVAL_SECONDS:-5}"

: "${BACKEND_IMAGE:?BACKEND_IMAGE is required}"
: "${FRONTEND_IMAGE:?FRONTEND_IMAGE is required}"
: "${DOMAIN:?DOMAIN is required}"

if [ ! -f "${ENV_FILE}" ]; then
    echo "Missing deployment environment file: ${ENV_FILE}" >&2
    exit 1
fi

if [ ! -f "${SCRIPT_DIR}/secrets/jwt-private.pem" ] \
    || [ ! -f "${SCRIPT_DIR}/secrets/jwt-public.pem" ]; then
    echo "Missing JWT keys. Run generate-jwt-keys.sh once on the server." >&2
    exit 1
fi

previous_backend_image=""
previous_frontend_image=""
if [ -f "${STATE_FILE}" ]; then
    previous_backend_image=$(sed -n '1p' "${STATE_FILE}")
    previous_frontend_image=$(sed -n '2p' "${STATE_FILE}")
fi

compose() {
    docker compose \
        --env-file "${ENV_FILE}" \
        -f "${COMPOSE_FILE}" \
        "$@"
}

wait_for_health() {
    attempt=1
    while [ "${attempt}" -le "${HEALTH_ATTEMPTS}" ]; do
        if health_response=$(curl --fail --silent --show-error \
            "https://${DOMAIN}/actuator/health"); then
            if printf '%s' "${health_response}" \
                | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
                return 0
            fi
        fi

        sleep "${HEALTH_INTERVAL_SECONDS}"
        attempt=$((attempt + 1))
    done

    return 1
}

export BACKEND_IMAGE
export FRONTEND_IMAGE
export DOMAIN

docker image inspect "${BACKEND_IMAGE}" >/dev/null
docker image inspect "${FRONTEND_IMAGE}" >/dev/null
compose up -d --remove-orphans
compose exec -T caddy \
    caddy reload \
    --config /etc/caddy/Caddyfile \
    --adapter caddyfile

if wait_for_health; then
    state_tmp="${STATE_FILE}.tmp"
    printf '%s\n%s\n' \
        "${BACKEND_IMAGE}" \
        "${FRONTEND_IMAGE}" >"${state_tmp}"
    mv "${state_tmp}" "${STATE_FILE}"
    compose ps
    echo "Cam-ON deployment succeeded."
    exit 0
fi

echo "Health check failed after deployment." >&2
compose logs --tail=200 backend caddy >&2

if [ -n "${previous_backend_image}" ] \
    && [ -n "${previous_frontend_image}" ]; then
    echo "Rolling back to the previously deployed images." >&2
    BACKEND_IMAGE="${previous_backend_image}"
    FRONTEND_IMAGE="${previous_frontend_image}"
    export BACKEND_IMAGE
    export FRONTEND_IMAGE

    docker image inspect "${BACKEND_IMAGE}" >/dev/null
    docker image inspect "${FRONTEND_IMAGE}" >/dev/null
    compose up -d --remove-orphans

    if wait_for_health; then
        echo "Rollback succeeded." >&2
    else
        echo "Rollback health check also failed." >&2
    fi
else
    echo "No previous deployment state is available for rollback." >&2
fi

exit 1
