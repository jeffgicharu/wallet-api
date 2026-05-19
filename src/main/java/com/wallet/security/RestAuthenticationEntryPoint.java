package com.wallet.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.dto.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Returns a clean JSON 401 for unauthenticated requests instead of
 * Spring Security's default empty-body 403. Without this, a missing,
 * malformed, or expired token produced a bare "403 Forbidden" with no
 * body — a JSON API leaking a blank HTML-shaped error. Now every
 * unauthenticated request gets the same {@link ApiResponse} envelope as
 * the rest of the API.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error("Authentication required. Provide a valid Bearer token."));
    }
}
