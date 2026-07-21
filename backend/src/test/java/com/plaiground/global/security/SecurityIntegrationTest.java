package com.plaiground.global.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.plaiground.domain.session.repository.SessionRepository;
import com.plaiground.global.security.jwt.JwtTokenProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @MockitoBean
    private SessionRepository sessionRepository;

    @Test
    void rejectsRequestWithoutAccessToken() throws Exception {
        mockMvc.perform(get("/api/protected-test"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsMalformedAccessToken() throws Exception {
        mockMvc.perform(get("/api/protected-test")
                .header("Authorization", "Bearer malformed-token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsValidJwtWhenRedisSessionIsMissing() throws Exception {
        UUID participantId = UUID.randomUUID();
        when(sessionRepository.findByParticipantId(participantId))
            .thenReturn(Optional.empty());
        String accessToken = tokenProvider.createAccessToken(participantId);

        mockMvc.perform(get("/api/protected-test")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
