package com.camon.global.exception;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.camon.domain.session.dto.CreateSessionRequest;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void returnsBusinessError() throws Exception {
        mockMvc.perform(get("/test/business-error"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("존재하지 않는 방입니다."))
            .andExpect(jsonPath("$.timestamp", notNullValue()));
    }

    @Test
    void returnsInvalidRequestForValidationFailure() throws Exception {
        mockMvc.perform(post("/test/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("잘못된 요청입니다."));
    }

    @Test
    void returnsInvalidRequestForMalformedJson() throws Exception {
        mockMvc.perform(post("/test/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void hidesUnexpectedExceptionDetails() throws Exception {
        mockMvc.perform(get("/test/unexpected-error"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/business-error")
        void businessError() {
            throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
        }

        @PostMapping("/sessions")
        void createSession(@Valid @RequestBody CreateSessionRequest request) {
        }

        @GetMapping("/unexpected-error")
        void unexpectedError() {
            throw new IllegalStateException("sensitive internal detail");
        }
    }
}
