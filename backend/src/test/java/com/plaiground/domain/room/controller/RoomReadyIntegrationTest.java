package com.plaiground.domain.room.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plaiground.domain.room.repository.ParticipantRepository;
import com.plaiground.domain.room.repository.ReadyUpdateResult;
import com.plaiground.domain.room.repository.ReadyUpdateStatus;
import com.plaiground.domain.room.repository.RoomRepository;
import com.plaiground.domain.room.service.RoomCodeGenerator;
import com.plaiground.domain.room.service.RoomInviteLinkGenerator;
import com.plaiground.domain.room.ws.RoomEventPublisher;
import com.plaiground.domain.session.domain.GuestSession;
import com.plaiground.domain.session.repository.SessionRepository;
import com.plaiground.global.security.jwt.JwtTokenProvider;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RoomReadyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @MockitoBean
    private RoomRepository roomRepository;

    @MockitoBean
    private ParticipantRepository participantRepository;

    @MockitoBean
    private SessionRepository sessionRepository;

    @MockitoBean
    private RoomCodeGenerator roomCodeGenerator;

    @MockitoBean
    private RoomInviteLinkGenerator roomInviteLinkGenerator;

    @MockitoBean
    private RoomEventPublisher roomEventPublisher;

    @Test
    void updatesReadyThroughControllerAndService() throws Exception {
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        String accessToken = authenticatedToken(participantId);
        when(participantRepository.updateReady(roomId, participantId, true))
            .thenReturn(new ReadyUpdateResult(
                ReadyUpdateStatus.SUCCESS,
                true,
                true
            ));

        mockMvc.perform(patch(
                "/api/rooms/{roomId}/members/me/ready",
                roomId
            )
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ready\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.participantId")
                .value(participantId.toString()))
            .andExpect(jsonPath("$.data.ready").value(true))
            .andExpect(jsonPath("$.data.allReady").value(true));

        verify(roomEventPublisher).publishMemberReadyUpdated(
            roomId,
            participantId,
            true,
            true
        );
    }

    @Test
    void rejectsReadyUpdateByNonParticipant() throws Exception {
        UUID roomId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        String accessToken = authenticatedToken(participantId);
        when(participantRepository.updateReady(roomId, participantId, true))
            .thenReturn(new ReadyUpdateResult(
                ReadyUpdateStatus.PARTICIPANT_NOT_FOUND,
                false,
                false
            ));

        mockMvc.perform(patch(
                "/api/rooms/{roomId}/members/me/ready",
                roomId
            )
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ready\":true}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ROOM_ACCESS_DENIED"));
    }

    private String authenticatedToken(UUID participantId) {
        when(sessionRepository.findByParticipantId(participantId)).thenReturn(
            Optional.of(new GuestSession(
                participantId,
                "guest",
                Instant.now()
            ))
        );
        return tokenProvider.createAccessToken(participantId);
    }
}
