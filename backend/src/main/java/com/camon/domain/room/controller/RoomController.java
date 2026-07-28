package com.camon.domain.room.controller;

import com.camon.domain.room.dto.CreateRoomRequest;
import com.camon.domain.room.dto.CreateRoomResponse;
import com.camon.domain.room.dto.JoinRoomRequest;
import com.camon.domain.room.dto.JoinRoomResponse;
import com.camon.domain.room.dto.RoomSnapshotResponse;
import com.camon.domain.room.dto.UpdateReadyRequest;
import com.camon.domain.room.dto.UpdateReadyResponse;
import com.camon.domain.room.service.RoomService;
import com.camon.global.apiresponse.ApiResponse;
import com.camon.global.security.GuestPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreateRoomResponse>> createRoom(
        @AuthenticationPrincipal GuestPrincipal principal,
        @Valid @RequestBody CreateRoomRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(roomService.createRoom(
                principal.participantId(),
                request
            )));
    }

    @PostMapping("/join")
    public ResponseEntity<ApiResponse<JoinRoomResponse>> joinRoom(
        @AuthenticationPrincipal GuestPrincipal principal,
        @Valid @RequestBody JoinRoomRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(roomService.joinRoom(
            principal.participantId(),
            request
        )));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<ApiResponse<RoomSnapshotResponse>> getRoom(
        @AuthenticationPrincipal GuestPrincipal principal,
        @PathVariable UUID roomId
    ) {
        return ResponseEntity.ok(ApiResponse.ok(roomService.getRoom(
            roomId,
            principal.participantId()
        )));
    }

    @DeleteMapping("/{roomId}/members/me")
    public ResponseEntity<Void> leaveRoom(
        @AuthenticationPrincipal GuestPrincipal principal,
        @PathVariable UUID roomId
    ) {
        roomService.leaveRoom(roomId, principal.participantId());
        return ResponseEntity.noContent().build();
    }

    // 방장의 참가자 강퇴. "me"가 아닌 특정 participantId를 지우는 유일한 멤버 삭제 경로 —
    // 방장 검증은 서비스(원자적 Lua 스크립트)가 한다.
    @DeleteMapping("/{roomId}/members/{participantId}")
    public ResponseEntity<Void> kickMember(
        @AuthenticationPrincipal GuestPrincipal principal,
        @PathVariable UUID roomId,
        @PathVariable UUID participantId
    ) {
        roomService.kick(roomId, principal.participantId(), participantId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{roomId}/members/me/ready")
    public ResponseEntity<ApiResponse<UpdateReadyResponse>> updateReady(
        @AuthenticationPrincipal GuestPrincipal principal,
        @PathVariable UUID roomId,
        @Valid @RequestBody UpdateReadyRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(roomService.updateReady(
            roomId,
            principal.participantId(),
            request
        )));
    }
}
