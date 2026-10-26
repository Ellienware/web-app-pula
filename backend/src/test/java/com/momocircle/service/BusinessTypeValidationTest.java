package com.momocircle.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.momocircle.domain.enums.BusinessType;
import com.momocircle.dto.MarketDtos.CreateBusinessRequest;
import com.momocircle.exception.GlobalExceptionHandler;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Task B (F-02): businessType is mandatory on POST /api/businesses. Covers bean validation (missing/null), JSON binding of
 * unsupported identifiers through the real Jackson mapper + the global handler, and every valid identifier.
 * Not executed in the authoring sandbox (no Maven) — see the phase report.
 */
class BusinessTypeValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String BASE = "\"name\":\"Test\",\"category\":\"Groceries\"";

    @Test
    void nullTypeFailsValidationWithClearFieldMessage() {
        var req = new CreateBusinessRequest("Test", null, null, "Groceries", null, null, null, null, null, null,
                null, null, null, null, null);

        var violations = validator.validate(req);

        assertEquals(1, violations.size());
        var v = violations.iterator().next();
        assertEquals("businessType", v.getPropertyPath().toString());
        assertEquals("Choose a business type", v.getMessage());
    }

    @Test
    void missingKeyInJsonBecomesNullAndFailsValidation() throws Exception {
        CreateBusinessRequest req = mapper.readValue("{" + BASE + "}", CreateBusinessRequest.class);
        assertEquals(null, req.businessType());
        assertEquals(1, validator.validate(req).size());
    }

    @Test
    void explicitJsonNullFailsValidation() throws Exception {
        CreateBusinessRequest req = mapper.readValue("{" + BASE + ",\"businessType\":null}", CreateBusinessRequest.class);
        assertEquals(1, validator.validate(req).size());
    }

    @Test
    void everySupportedTypeBindsAndValidates() throws Exception {
        for (BusinessType t : BusinessType.values()) {
            CreateBusinessRequest req = mapper.readValue(
                    "{" + BASE + ",\"businessType\":\"" + t.name() + "\"}", CreateBusinessRequest.class);
            assertEquals(t, req.businessType());
            assertTrue(validator.validate(req).isEmpty(), t.name());
        }
    }

    @Test
    void unsupportedIdentifierIsRejectedByBindingAndHandlerExplainsAllowedValues() {
        var bindFailure = assertThrows(com.fasterxml.jackson.databind.exc.InvalidFormatException.class,
                () -> mapper.readValue("{" + BASE + ",\"businessType\":\"FOOD_TRUCK\"}", CreateBusinessRequest.class));

        var notReadable = new HttpMessageNotReadableException("bad body", bindFailure, mock(HttpInputMessage.class));
        ResponseEntity<Map<String, Object>> response = new GlobalExceptionHandler().handleUnreadableBody(notReadable);

        assertEquals(400, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        String serialized = body.toString();
        assertTrue(serialized.contains("businessType"), serialized);
        assertTrue(serialized.contains("RESTAURANT") && serialized.contains("SPAZA"), "lists allowed values: " + serialized);
        assertTrue(!serialized.contains("FOOD_TRUCK"), "must not echo the submitted value");
    }

    @Test
    void lowercaseIdentifierIsNotSilentlyAccepted() {
        assertThrows(com.fasterxml.jackson.databind.exc.InvalidFormatException.class,
                () -> mapper.readValue("{" + BASE + ",\"businessType\":\"restaurant\"}", CreateBusinessRequest.class));
    }
}
