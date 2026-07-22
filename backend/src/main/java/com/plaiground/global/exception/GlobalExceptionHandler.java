package com.plaiground.global.exception;

import com.plaiground.global.apiresponse.ErrorResponse;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode code = exception.errorCode();
        log.info("[ExceptionHandler] {} : {}", code.name(), code.message());
        return ResponseEntity.status(code.status()).body(new ErrorResponse(
            code.name(),
            code.message(),
            Instant.now()
        ));
    }
}
