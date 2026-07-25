# Cam-ON Backend

## Stack

- Java 21
- Spring Boot 3.5.16
- Spring WebSocket/STOMP
- Spring Data Redis
- OpenVidu 3.7 (external deployment)
- LiveKit Java/Kotlin Server SDK 0.13.0

`@stomp/stompjs 7.3` and `livekit-client 2.20` are frontend dependencies and are not installed here.

## Local infrastructure

Start the application Redis instance:

```powershell
docker compose up -d redis
```

OpenVidu runs as a separate Docker deployment. Configure this backend with:

```powershell
$env:LIVEKIT_URL = "http://localhost:7880"
$env:LIVEKIT_API_KEY = "devkey"
$env:LIVEKIT_API_SECRET = "secret"
```

Do not reuse OpenVidu's internal Redis for Cam-ON room state.

## Run

```powershell
.\gradlew.bat bootRun
```

Health check: `GET http://localhost:8080/actuator/health`
