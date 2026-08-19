package molclass.predictor;

import java.sql.SQLException;
import java.time.Instant;
import java.util.NoSuchElementException;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = V3PredictionController.class)
public final class V3ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(V3ApiExceptionHandler.class);

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ApiError> notFound(NoSuchElementException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidRequest(IllegalArgumentException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler(SQLException.class)
    ResponseEntity<ApiError> databaseUnavailable(SQLException exception, HttpServletRequest request) {
        LOGGER.error("v3 database operation failed", exception);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                "The prediction database is temporarily unavailable.", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> contractFailure(IllegalStateException exception, HttpServletRequest request) {
        LOGGER.error("v3 model contract failed", exception);
        return response(HttpStatus.CONFLICT, "MODEL_CONTRACT_FAILURE",
                "The published model failed its integrity or compatibility contract.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("unexpected v3 API failure", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "The prediction request could not be completed.", request);
    }

    private static ResponseEntity<ApiError> response(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        String safeMessage = message == null || message.isBlank() ? status.getReasonPhrase() : message;
        return ResponseEntity.status(status).body(new ApiError(
                Instant.now(), status.value(), code, safeMessage, request.getRequestURI()));
    }

    public record ApiError(Instant timestamp, int status, String code, String message, String path) {}
}
