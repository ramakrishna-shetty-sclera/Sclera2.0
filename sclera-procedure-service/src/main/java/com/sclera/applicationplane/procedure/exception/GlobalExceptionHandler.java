package com.sclera.applicationplane.procedure.exception;

import com.sclera.controlplane.common.exception.GlobalExceptionHandlerBase;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns on the shared error envelope. Inherits handlers for
 * validation / not-found / conflict / forbidden / etc.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends GlobalExceptionHandlerBase {
}
