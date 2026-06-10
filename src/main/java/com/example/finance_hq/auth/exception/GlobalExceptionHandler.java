package com.example.finance_hq.auth.exception;

import com.example.finance_hq.obligation.InvalidObligationException;
import com.example.finance_hq.obligation.ObligationNotFoundException;
import com.example.finance_hq.transaction.InvalidTransactionException;
import com.example.finance_hq.transaction.TransactionNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.format.DateTimeParseException;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        String detail = (msg.contains("users_email_key") || msg.contains("users_email"))
                ? "Email already registered" : "Duplicate entry";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setTitle("Conflict");
        return ResponseEntity.status(409).body(problem);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        problem.setTitle("Unauthorized");
        return ResponseEntity.status(401).body(problem);
    }

    @ExceptionHandler({UsernameNotFoundException.class, BadCredentialsException.class})
    public ResponseEntity<ProblemDetail> handleAuthFailure(RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        problem.setTitle("Unauthorized");
        return ResponseEntity.status(401).body(problem);
    }

    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<ProblemDetail> handleDateTimeParse(DateTimeParseException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid date/time format: " + ex.getParsedString());
        problem.setTitle("Bad Request");
        return ResponseEntity.status(400).body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body");
        problem.setTitle("Bad Request");
        return ResponseEntity.status(400).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setTitle("Validation Failed");
        problem.setProperty("details", details);
        return ResponseEntity.status(400).body(problem);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found");
        problem.setTitle("Not Found");
        return ResponseEntity.status(404).body(problem);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        problem.setTitle("Method Not Allowed");
        return ResponseEntity.status(405).body(problem);
    }

    @ExceptionHandler(ObligationNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleObligationNotFound(ObligationNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Obligation not found");
        problem.setTitle("Not Found");
        return ResponseEntity.status(404).body(problem);
    }

    @ExceptionHandler(InvalidObligationException.class)
    public ResponseEntity<ProblemDetail> handleInvalidObligation(InvalidObligationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Validation Failed");
        return ResponseEntity.status(400).body(problem);
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleTransactionNotFound(TransactionNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Transaction not found");
        problem.setTitle("Not Found");
        return ResponseEntity.status(404).body(problem);
    }

    @ExceptionHandler(InvalidTransactionException.class)
    public ResponseEntity<ProblemDetail> handleInvalidTransaction(InvalidTransactionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Validation Failed");
        return ResponseEntity.status(400).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        problem.setTitle("Internal Server Error");
        return ResponseEntity.status(500).body(problem);
    }
}
