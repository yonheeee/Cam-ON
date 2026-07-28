package com.camon.domain.session.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.camon.domain.session.dto.CreateSessionResponse;
import com.camon.domain.session.service.SessionService;
import com.camon.global.exception.GlobalExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SessionControllerTest {

    private MockMvc mockMvc;
    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new SessionController(sessionService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void createsGuestSession() throws Exception {
        UUID participantId = UUID.randomUUID();
        when(sessionService.createSession(any())).thenReturn(
            new CreateSessionResponse(participantId, "플레이어1", "jwt-token")
        );

        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"플레이어1\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.participantId")
                .value(participantId.toString()))
            .andExpect(jsonPath("$.data.nickname").value("플레이어1"))
            .andExpect(jsonPath("$.data.accessToken").value("jwt-token"));
    }

    @Test
    void rejectsInvalidNickname() throws Exception {
        mockMvc.perform(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"공백 닉네임\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
