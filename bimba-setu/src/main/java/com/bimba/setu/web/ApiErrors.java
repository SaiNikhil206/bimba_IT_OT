package com.bimba.setu.web;

import com.bimba.setu.agents.AgentRegistry;
import com.bimba.setu.security.SecurityService.ApiException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.bimba.setu.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/** One JSON error shape for the whole API: {"error": "...", "code": "..."} (+ extra fields such as "orgs"). */
@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger("BIMBA.Api");
    static final int MAX_BODY = 256 * 1024;

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.status, e.code, e.getMessage(), Map.of());
    }

    @ExceptionHandler(AgentRegistry.ApiError.class)
    ResponseEntity<Map<String, Object>> agent(AgentRegistry.ApiError e) {
        return body(e.status, null, e.getMessage(), e.extra);
    }

    @ExceptionHandler({AuthorizationDeniedException.class, AccessDeniedException.class})
    ResponseEntity<Map<String, Object>> denied(Exception e) {
        return body(403, "forbidden", "Your role does not allow this.", Map.of());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> database(DataAccessException e) {
        log.warn("database error: {}", e.getMostSpecificCause().getMessage());
        return body(503, "database", "The database is not reachable. Try again shortly.", Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
        return body(404, "not_found", "Not found.", Map.of());
    }

    /** a body that could not be read: 413 when it passed the size limit (web/RequestSizeLimit), otherwise 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RequestSizeLimit.TooLarge) {
                return body(413, "body", t.getMessage(), Map.of());
            }
        }
        return body(400, "body", "The request body could not be read.", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("request failed", e);
        return body(500, "internal", "Something went wrong on Setu. See Setu log.", Map.of());
    }

    static ResponseEntity<Map<String, Object>> body(int status, String code, String message, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        if (code != null) {
            m.put("code", code);
        }
        m.putAll(extra);
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(m);
    }

    /** Request body as a JSON object (max 256 KB), the shape every service method takes. */
    static JsonObject parse(String body) throws ApiException {
        if (body != null && body.length() > MAX_BODY) {
            throw new ApiException(413, "body", "The request body is larger than 256 KB.");
        }
        try {
            JsonObject o = Json.GSON.fromJson(body == null || body.isBlank() ? "{}" : body, JsonObject.class);
            return o == null ? new JsonObject() : o;
        } catch (JsonParseException | ClassCastException e) {
            throw new ApiException(400, "body", "The request body must be a JSON object.");
        }
    }
}
