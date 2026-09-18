package com.deni.backend.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorEnvelope> handleApiException(ApiException exception, HttpServletRequest request) {
		return response(exception.getStatus(), exception.getCode(), exception.getMessage(),
				exception.getFieldErrors(), request);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorEnvelope> handleValidation(MethodArgumentNotValidException exception,
			HttpServletRequest request) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		exception.getBindingResult().getFieldErrors()
				.forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "입력값을 확인해 주세요.", fieldErrors, request);
	}

	@ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
			MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
	ResponseEntity<ErrorEnvelope> handleBadRequest(Exception exception, HttpServletRequest request) {
		return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 형식이 올바르지 않습니다.", null, request);
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ErrorEnvelope> handleConflict(DataIntegrityViolationException exception,
			HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, "CONFLICT", "이미 처리된 요청이거나 저장할 수 없는 상태입니다.", null,
				request);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ErrorEnvelope> handleUnsupportedMethod(HttpRequestMethodNotSupportedException exception,
			HttpServletRequest request) {
		var result = response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다.", null, request);
		HttpHeaders headers = new HttpHeaders();
		headers.addAll(result.getHeaders());
		if (exception.getSupportedHttpMethods() != null) headers.setAllow(exception.getSupportedHttpMethods());
		return new ResponseEntity<>(result.getBody(), headers, HttpStatus.METHOD_NOT_ALLOWED);
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<ErrorEnvelope> handleConcurrentUpdate(OptimisticLockingFailureException exception,
			HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, "CONFLICT", "정보가 다른 요청에서 변경되었습니다. 다시 조회해 주세요.",
				null, request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorEnvelope> handleUnexpected(Exception exception, HttpServletRequest request) {
		String requestId = requestId(request);
		log.error("Unexpected API error. requestId={}", requestId, exception);
		return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "일시적인 서버 오류가 발생했습니다.", null,
				requestId);
	}

	private ResponseEntity<ErrorEnvelope> response(HttpStatus status, String code, String message,
			Map<String, String> fieldErrors, HttpServletRequest request) {
		return response(status, code, message, fieldErrors, requestId(request));
	}

	private ResponseEntity<ErrorEnvelope> response(HttpStatus status, String code, String message,
			Map<String, String> fieldErrors, String requestId) {
		ErrorEnvelope body = new ErrorEnvelope(new ErrorBody(code, message, requestId, fieldErrors));
		return ResponseEntity.status(status).header("X-Request-Id", requestId).body(body);
	}

	private String requestId(HttpServletRequest request) {
		String provided = request.getHeader("X-Request-Id");
		return provided == null || provided.isBlank() ? "req_" + UUID.randomUUID() : provided;
	}

	public record ErrorEnvelope(ErrorBody error) {
	}

	public record ErrorBody(String code, String message, String requestId, Map<String, String> fieldErrors) {
	}
}
