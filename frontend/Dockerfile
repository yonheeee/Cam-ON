FROM node:24-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# Vite는 빌드 시점에 VITE_* 값을 번들에 굽는다 — 런타임 env로는 못 바꾼다.
ARG VITE_API_BASE_URL
ARG VITE_WS_BASE_URL
# 백엔드가 토큰을 서명할 때 쓰는 LiveKit 프로젝트와 같은 URL이어야 한다(운영에서는 deploy .env의
# LIVEKIT_URL을 그대로 넘긴다). 어긋나면 토큰은 정상인데 연결만 거부돼서 원인을 찾기 어렵다.
ARG VITE_LIVEKIT_URL
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
ENV VITE_WS_BASE_URL=${VITE_WS_BASE_URL}
ENV VITE_LIVEKIT_URL=${VITE_LIVEKIT_URL}

RUN npm run build

# 상시 컨테이너가 아니라, dist를 named volume(/output)에 복사만 하고 종료하는
# 1회성 빌드 잡 — nginx-certbot 컨테이너가 그 volume을 read-only로 마운트해서 서빙한다.
FROM alpine:3.20

COPY --from=build /app/dist /dist

CMD ["sh", "-c", "cp -r /dist/. /output/ && echo 'frontend build copied to /output'"]
