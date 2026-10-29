package com.momocircle.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.momocircle.domain.Business;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessRegistrationStatus;
import com.momocircle.domain.enums.BusinessType;
import com.momocircle.domain.enums.PlatformRole;
import com.momocircle.dto.MarketDtos.BusinessResponse;
import com.momocircle.dto.MarketDtos.PublicBusinessResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.UploadedImageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task A (F-03): anonymous and non-owner callers get {@link PublicBusinessResponse}, which never carries the owner's identity or
 * the registration details; the owner and admins keep the full {@link BusinessResponse}; inactive (deactivated/suspended)
 * businesses are not exposed to anyone else.
 *
 * Uses a synthetic, obviously fake registration number. Not executed in the authoring sandbox (no Maven) — see the phase report.
 */
class PublicBusinessVisibilityTest {

    private static final String FAKE_REG = "TEST-REG-0000/00";
    private static final Set<String> PROTECTED_FIELDS =
            Set.of("ownerId", "ownerName", "registrationNumber", "registrationStatus");

    private BusinessRepository repo;
    private BusinessService service;
    private User owner;
    private User stranger;
    private User admin;
    private Business business;

    @BeforeEach
    void setUp() {
        repo = mock(BusinessRepository.class);
        service = new BusinessService(repo, mock(UploadedImageRepository.class));
        owner = user("owner-1", "Owner One", PlatformRole.USER);
        stranger = user("other-2", "Someone Else", PlatformRole.USER);
        admin = user("admin-3", "Admin Three", PlatformRole.ADMIN);

        business = new Business();
        business.setId("biz-1");
        business.setOwner(owner);
        business.setName("Test Shop");
        business.setCategory("Groceries");
        business.setBusinessType(BusinessType.SPAZA);
        business.setContactPhone("0820000000");
        business.setRegistrationStatus(BusinessRegistrationStatus.FORMALLY_REGISTERED);
        business.setRegistrationNumber(FAKE_REG);
        when(repo.findById("biz-1")).thenReturn(Optional.of(business));
    }

    private static User user(String id, String name, PlatformRole role) {
        User u = new User();
        u.setId(id);
        u.setFullName(name);
        u.setRole(role);
        return u;
    }

    private static JsonNode json(Object o) {
        ObjectMapper m = new ObjectMapper().registerModule(new JavaTimeModule());
        return m.valueToTree(o);
    }

    @Test
    void anonymousCallerGetsPublicDtoWithoutProtectedFields() {
        Object result = service.getForViewer("biz-1", null);

        assertInstanceOf(PublicBusinessResponse.class, result);
        JsonNode node = json(result);
        for (String f : PROTECTED_FIELDS) {
            assertFalse(node.has(f), "anonymous response must not serialize " + f);
        }
        assertFalse(node.toString().contains(FAKE_REG), "registration number must not appear anywhere in the payload");
        // storefront fields the web needs are still present
        assertEquals("Test Shop", node.get("name").asText());
        assertEquals("SPAZA", node.get("businessType").asText());
        assertEquals("0820000000", node.get("contactPhone").asText());
        assertTrue(node.has("fulfilmentOptions"));
        assertTrue(node.has("minimumOrderAmount"));
    }

    @Test
    void publicDtoComponentsNeverIncludeProtectedFields() {
        for (var component : PublicBusinessResponse.class.getRecordComponents()) {
            assertFalse(PROTECTED_FIELDS.contains(component.getName()), component.getName());
        }
    }

    @Test
    void otherSignedInUserAlsoGetsPublicDto() {
        assertInstanceOf(PublicBusinessResponse.class, service.getForViewer("biz-1", stranger));
    }

    @Test
    void ownerKeepsFullResponseIncludingRegistrationDetails() {
        Object result = service.getForViewer("biz-1", owner);

        assertInstanceOf(BusinessResponse.class, result);
        JsonNode node = json(result);
        assertEquals("owner-1", node.get("ownerId").asText());
        assertEquals(FAKE_REG, node.get("registrationNumber").asText());
        assertEquals("FORMALLY_REGISTERED", node.get("registrationStatus").asText());
    }

    @Test
    void adminGetsFullResponse() {
        assertInstanceOf(BusinessResponse.class, service.getForViewer("biz-1", admin));
    }

    @Test
    void inactiveBusinessIsNotFoundForAnonymousAndStrangers() {
        business.setActive(false);

        ApiException anon = assertThrows(ApiException.class, () -> service.getForViewer("biz-1", null));
        ApiException other = assertThrows(ApiException.class, () -> service.getForViewer("biz-1", stranger));
        assertEquals(404, anon.getStatus().value());
        assertEquals(404, other.getStatus().value());
    }

    @Test
    void inactiveBusinessRemainsVisibleToOwnerAndAdmin() {
        business.setActive(false);

        assertInstanceOf(BusinessResponse.class, service.getForViewer("biz-1", owner));
        assertInstanceOf(BusinessResponse.class, service.getForViewer("biz-1", admin));
    }

    @Test
    void missingBusinessIsNotFound() {
        when(repo.findById("nope")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.getForViewer("nope", null)).getStatus().value());
    }

    @Test
    void browseReturnsPublicDtosOnly() {
        when(repo.findByActiveTrue(PageRequest.of(0, 20))).thenReturn(new PageImpl<>(List.of(business)));

        Page<PublicBusinessResponse> page = service.browse(null, null, null, PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        JsonNode node = json(page.getContent().get(0));
        for (String f : PROTECTED_FIELDS) {
            assertFalse(node.has(f), f);
        }
    }

    @Test
    void browseUsesOnlyActiveOnlyRepositoryQueries() {
        // The service must never fall back to an unfiltered findAll for public browsing.
        when(repo.findByActiveTrue(PageRequest.of(0, 20))).thenReturn(new PageImpl<>(List.of()));
        service.browse(null, null, null, PageRequest.of(0, 20));
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).findAll(org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class));
    }
}
