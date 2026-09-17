package com.deni.backend.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final Map<String, String> fieldErrors;

	private ApiException(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
		super(message);
		this.status = status;
		this.code = code;
		this.fieldErrors = fieldErrors;
	}

	public static ApiException validation(String message, Map<String, String> fieldErrors) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, fieldErrors);
	}

	public static ApiException notFound(String message) {
		return new ApiException(HttpStatus.NOT_FOUND, "CHILD_NOT_FOUND", message, null);
	}

	public static ApiException notFound(String code, String message) {
		return new ApiException(HttpStatus.NOT_FOUND, code, message, null);
	}

	public static ApiException conflict(String code, String message) {
		return new ApiException(HttpStatus.CONFLICT, code, message, null);
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public Map<String, String> getFieldErrors() {
		return fieldErrors;
	}
}
