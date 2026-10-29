package com.momocircle.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The complete workflow through the REAL security filter chain and real controllers: registration and login, business creation,
 * stokvel creation, discovery, membership requests, approval, invite links, and what members and outsiders can then reach.
 * NOT RUN in the sandbox that produced it (no Maven); run with {@code mvn -Dtest=StokvelMembershipEndToEndTest test}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:stokvele2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
class StokvelMembershipEndToEndTest {

    private static final String S = "/api/stokvels";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    record U(String id, String token, String email, String name) { }

    // ------------------------------------------------------------------ fixtures: real registration and login

    U register(String name) throws Exception {
        String email = UUID.randomUUID() + "@e2e.local";
        String phone = "+2776" + (long) (Math.random() * 1e7);
        MvcResult reg = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"fullName\":\"" + name + "\",\"phoneNumber\":\"" + phone + "\",\"password\":\"Passw0rd!x\"}")).andReturn();
        assertTrue(status(reg) == 200 || status(reg) == 201, "register: " + body(reg));
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Passw0rd!x\"}")).andReturn();
        assertEquals(200, status(login), body(login));
        return new U(json(login, "$.user.id"), json(login, "$.token"), email, name);
    }

    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, U u) { return u == null ? b : b.header("Authorization", "Bearer " + u.token()); }
    MvcResult doGet(U u, String path) throws Exception { return mvc.perform(auth(get(path), u)).andReturn(); }
    MvcResult doPost(U u, String path, String json) throws Exception {
        return mvc.perform(auth(post(path).contentType(MediaType.APPLICATION_JSON).content(json == null ? "{}" : json), u)).andReturn();
    }
    MvcResult doPut(U u, String path, String json) throws Exception {
        return mvc.perform(auth(put(path).contentType(MediaType.APPLICATION_JSON).content(json), u)).andReturn();
    }
    static String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }
    static int status(MvcResult r) { return r.getResponse().getStatus(); }
    static String json(MvcResult r, String path) throws Exception { return String.valueOf((Object) JsonPath.read(body(r), path)); }

    String createStokvel(U owner, String name, String type) throws Exception {
        MvcResult r = doPost(owner, S, "{\"name\":\"" + name + "\",\"description\":\"PRIVATE members-only notes\",\"stokvelType\":\"" + type
                + "\",\"currency\":\"ZAR\",\"contributionAmount\":250.00,\"frequency\":\"MONTHLY\"}");
        assertEquals(201, status(r), body(r));
        return json(r, "$.circleId");
    }

    void openToDiscovery(U owner, String id, int max, boolean inviteNeedsApproval) throws Exception {
        MvcResult r = doPut(owner, S + "/" + id + "/membership-settings", "{\"discoverable\":true,\"area\":\"Soweto\",\"publicSummary\":\"Monthly rotating savings for neighbours\","
                + "\"membershipRequirements\":\"Must live in Soweto\",\"maxMembers\":" + max + ",\"inviteRequiresApproval\":" + inviteNeedsApproval + "}");
        assertEquals(200, status(r), body(r));
    }

    // ------------------------------------------------------------------ the complete workflow

    @Test
    void fromLoginThroughBusinessStokvelDiscoveryRequestApprovalAndMemberAccess() throws Exception {
        U owner = register("Thandi Mokoena"), applicant = register("Ann Applicant"), outsider = register("Zed Outsider");

        // 1. business creation (existing API) works for the authenticated owner and not for the anonymous caller
        assertTrue(status(doPost(null, "/api/businesses", "{\"name\":\"Thandi Spaza\",\"category\":\"Groceries\"}")) >= 400);
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j5Z8AAAAASUVORK5CYII="
        );
        MockMultipartFile logoFile = new MockMultipartFile(
                "file", "thandi-spaza.png", "image/png", png
        );
        MvcResult logo = mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/images/business-logo")
                        .file(logoFile)
                        .header("Authorization", "Bearer " + owner.token())
        ).andReturn();
        assertEquals(201, status(logo), body(logo));
        String logoUrl = json(logo, "$.url");

        MvcResult biz = doPost(owner, "/api/businesses", "{\"name\":\"Thandi Spaza\",\"category\":\"Groceries\",\"logoUrl\":\"" + logoUrl + "\",\"businessType\":\"INFORMAL_TRADER\",\"location\":\"Soweto\","
                + "\"contactPhone\":\"0821234567\",\"registrationStatus\":\"NOT_REGISTERED\",\"fulfilmentOptions\":[\"CUSTOMER_PICKUP\"],\"capabilities\":[\"CAN_SELL_TO_CONSUMERS\"]}");
        assertTrue(status(biz) == 200 || status(biz) == 201, body(biz));
        assertEquals("Thandi Spaza", json(doGet(owner, "/api/businesses/me"), "$.name"));
        assertEquals(409, status(doPost(owner, "/api/businesses",
                "{\"name\":\"Second\",\"category\":\"Groceries\",\"businessType\":\"INFORMAL_TRADER\"}")),
                "one business per owner");

        // 2. stokvel creation through the STOKVEL api; it appears in the creator's list and opens
        String id = createStokvel(owner, "Umoja Savers", "ROTATIONAL");
        assertTrue(body(doGet(owner, S)).contains(id), "the creator's stokvel list includes it");
        assertEquals(200, status(doGet(owner, S + "/" + id)));
        assertEquals(400, status(doPost(owner, "/api/circles", "{\"name\":\"x\",\"type\":\"STOKVEL\",\"currency\":\"ZAR\"}")), "the generic circle endpoint still refuses stokvels");

        // 3. private by default: invisible to discovery and to join attempts; no access to private data
        assertFalse(body(doGet(applicant, S + "/discover")).contains(id));
        assertEquals(404, status(doPost(applicant, S + "/" + id + "/join-requests", "{\"message\":\"hi\"}")));
        assertEquals(404, status(doGet(applicant, S + "/discover/" + id)));
        assertEquals(403, status(doGet(outsider, S + "/" + id)));
        assertEquals(403, status(doGet(outsider, "/api/circles/" + id + "/members")));

        // 4. the owner opts in; discovery now shows ONLY the public card
        openToDiscovery(owner, id, 10, false);
        MvcResult found = doGet(applicant, S + "/discover?q=umoja&type=ROTATIONAL");
        assertEquals(200, status(found));
        assertEquals(id, json(found, "$.items[0].circleId"));
        assertEquals("Monthly rotating savings for neighbours", json(found, "$.items[0].publicSummary"));
        assertEquals("Must live in Soweto", json(found, "$.items[0].requirements"));
        assertFalse(body(found).contains("PRIVATE members-only notes"), "private description is never exposed");
        assertFalse(body(found).contains(owner.email()) || body(found).contains(owner.id()), "no contact details or ids");
        assertTrue(body(found).contains("does not hold"), "tracked, not held");

        // 5. request, pending, duplicate refused, then the admin sees it
        MvcResult req = doPost(applicant, S + "/" + id + "/join-requests", "{\"message\":\"I live in Soweto\"}");
        assertEquals(201, status(req), body(req));
        String requestId = json(req, "$.requestId");
        assertEquals(409, status(doPost(applicant, S + "/" + id + "/join-requests", "{}")), "duplicate request");
        assertEquals(403, status(doGet(applicant, S + "/" + id)), "pending is not membership");
        assertEquals(403, status(doGet(applicant, S + "/" + id + "/join-requests")), "applicants cannot read the queue");
        MvcResult queue = doGet(owner, S + "/" + id + "/join-requests?status=PENDING");
        assertEquals(requestId, json(queue, "$[0].requestId"));
        assertEquals("Ann Applicant", json(queue, "$[0].applicantName"));

        // 6. unauthorised decisions fail; the owner approves; the applicant is now a MEMBER with member access
        assertEquals(403, status(doPost(applicant, S + "/" + id + "/join-requests/" + requestId + "/approve", "{}")));
        assertEquals(403, status(doPost(outsider, S + "/" + id + "/join-requests/" + requestId + "/approve", "{}")));
        MvcResult ok = doPost(owner, S + "/" + id + "/join-requests/" + requestId + "/approve", "{\"note\":\"Welcome\"}");
        assertEquals(200, status(ok), body(ok));
        assertEquals("APPROVED", json(ok, "$.status"));
        assertEquals(409, status(doPost(owner, S + "/" + id + "/join-requests/" + requestId + "/approve", "{}")), "no double approval");
        assertEquals(200, status(doGet(applicant, S + "/" + id)), "approved member can open the stokvel");
        assertTrue(body(doGet(owner, "/api/circles/" + id + "/members")).contains(applicant.id()), "appears in the existing membership list");
        assertTrue(body(doGet(applicant, S)).contains(id), "appears in the new member's stokvel list");
        assertEquals(403, status(doPost(applicant, S + "/" + id + "/invite-links", "{}")), "a plain member cannot invite");
        assertEquals(403, status(doGet(outsider, S + "/" + id)), "outsiders still cannot see the stokvel");
        assertFalse(body(doGet(applicant, S + "/discover")).contains(id), "members no longer see it in Discover");
    }

    @Test
    void inviteLinkFlowPreviewAcceptDuplicateExpiredAndRevoked() throws Exception {
        U owner = register("Thandi Mokoena"), friend = register("Fay Friend"), late = register("Lee Late"), member = register("Mia Member");
        String id = createStokvel(owner, "Closed Circle", "GENERAL");      // stays PRIVATE: invites work without discovery

        assertEquals(403, status(doPost(member, S + "/" + id + "/invite-links", "{}")), "non-members cannot create links");
        MvcResult made = doPost(owner, S + "/" + id + "/invite-links", "{\"expiresInDays\":7,\"maxUses\":1}");
        assertEquals(201, status(made), body(made));
        String code = json(made, "$.code");
        String linkId = json(made, "$.link.linkId");
        assertFalse(body(doGet(owner, S + "/" + id + "/invite-links")).contains(code.replace("-", "")), "the code is never listed");

        MvcResult preview = doPost(friend, S + "/invite-links/preview", "{\"code\":\"" + code.toLowerCase() + "\"}");
        assertEquals(200, status(preview), body(preview));
        assertEquals("Closed Circle", json(preview, "$.stokvelName"));
        assertEquals(403, status(doGet(friend, S + "/" + id)), "previewing adds nobody");

        MvcResult accepted = doPost(friend, S + "/invite-links/accept", "{\"code\":\"" + code + "\"}");
        assertEquals(200, status(accepted), body(accepted));
        assertEquals("JOINED", json(accepted, "$.outcome"));
        assertEquals(200, status(doGet(friend, S + "/" + id)));
        assertEquals("ALREADY_MEMBER", json(doPost(friend, S + "/invite-links/accept", "{\"code\":\"" + code + "\"}"), "$.outcome"));

        MvcResult exhausted = doPost(late, S + "/invite-links/accept", "{\"code\":\"" + code + "\"}");
        assertEquals(404, status(exhausted));
        assertTrue(body(exhausted).contains("not valid or has expired"));
        assertEquals(403, status(doGet(late, S + "/" + id)));

        // expiry and revocation
        MvcResult l2 = doPost(owner, S + "/" + id + "/invite-links", "{\"maxUses\":5}");
        jdbc.update("update stokvel_invite_links set expires_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(60)), json(l2, "$.link.linkId"));
        assertEquals(404, status(doPost(late, S + "/invite-links/accept", "{\"code\":\"" + json(l2, "$.code") + "\"}")), "expired");
        MvcResult l3 = doPost(owner, S + "/" + id + "/invite-links", "{\"maxUses\":5}");
        assertEquals(403, status(doPost(friend, S + "/" + id + "/invite-links/" + json(l3, "$.link.linkId") + "/revoke", "{}")), "a member cannot revoke");
        assertEquals(200, status(doPost(owner, S + "/" + id + "/invite-links/" + json(l3, "$.link.linkId") + "/revoke", "{}")));
        assertEquals(404, status(doPost(late, S + "/invite-links/accept", "{\"code\":\"" + json(l3, "$.code") + "\"}")), "revoked");
        
        MvcResult listing = doGet(owner, S + "/" + id + "/invite-links");

        String exhaustedStatus = JsonPath.read(
                body(listing),
                "$[?(@.linkId == '" + linkId + "')].status"
        ).toString();

        assertTrue(exhaustedStatus.contains("EXHAUSTED"),
                "The invite link should be exhausted. Listing: " + body(listing));

    }

    @Test
    void inviteRequiringApprovalCreatesARequestFirst() throws Exception {
        U owner = register("Thandi Mokoena"), guest = register("Gus Guest");
        String id = createStokvel(owner, "Vetted Circle", "GENERAL");
        openToDiscovery(owner, id, 10, true);
        String code = json(doPost(owner, S + "/" + id + "/invite-links", "{\"maxUses\":3}"), "$.code");
        MvcResult accepted = doPost(guest, S + "/invite-links/accept", "{\"code\":\"" + code + "\"}");
        assertEquals("REQUESTED", json(accepted, "$.outcome"));
        assertEquals(403, status(doGet(guest, S + "/" + id)), "not a member until approved");
        String requestId = json(accepted, "$.request.requestId");
        assertEquals(200, status(doPost(owner, S + "/" + id + "/join-requests/" + requestId + "/approve", "{}")));
        assertEquals(200, status(doGet(guest, S + "/" + id)));
    }

    @Test
    void rejectCancelAndMyRequestsWorkOverHttp() throws Exception {
        U owner = register("Thandi Mokoena"), a = register("Ann Applicant"), b = register("Bo Applicant"), stranger = register("Eve Stranger");
        String id = createStokvel(owner, "Open Circle", "GENERAL");
        openToDiscovery(owner, id, 10, false);
        String ra = json(doPost(a, S + "/" + id + "/join-requests", "{}"), "$.requestId");
        String rb = json(doPost(b, S + "/" + id + "/join-requests", "{}"), "$.requestId");
        assertEquals(404, status(doPost(stranger, S + "/" + id + "/join-requests/" + ra + "/cancel", "{}")), "cannot cancel someone else's request");
        assertEquals("CANCELLED", json(doPost(a, S + "/" + id + "/join-requests/" + ra + "/cancel", "{}"), "$.status"));
        assertEquals("REJECTED", json(doPost(owner, S + "/" + id + "/join-requests/" + rb + "/reject", "{\"note\":\"Not in area\"}"), "$.status"));
        assertEquals(429, status(doPost(b, S + "/" + id + "/join-requests", "{}")), "cooldown after rejection");
        assertEquals("CANCELLED", json(doGet(a, S + "/join-requests/mine"), "$[0].status"));
        assertEquals("REJECTED", json(doGet(b, S + "/join-requests/mine"), "$[0].status"));
        assertEquals("[]", body(doGet(stranger, S + "/join-requests/mine")));
        assertEquals(403, status(doGet(stranger, S + "/" + id + "/membership-settings")));
    }

    @Test
    void everyMembershipEndpointRequiresAuthentication() throws Exception {
        for (MvcResult r : new MvcResult[]{doGet(null, S + "/discover"), doGet(null, S + "/join-requests/mine"),
                doPost(null, S + "/invite-links/accept", "{\"code\":\"x\"}"), doPost(null, S + "/invite-links/preview", "{\"code\":\"x\"}"),
                doPost(null, S + "/abc/join-requests", "{}"), doPost(null, S + "/abc/invite-links", "{}")}) {
            int s = status(r);
            assertTrue(s == 401 || s == 403, "expected refusal, got " + s);
        }
    }

    @Test
    void invalidDiscoveryFiltersAndSettingsAreRejected() throws Exception {
        U owner = register("Thandi Mokoena");
        String id = createStokvel(owner, "Validation Circle", "GENERAL");
        assertEquals(400, status(doGet(owner, S + "/discover?type=NOT_A_TYPE")));
        assertEquals(400, status(doGet(owner, S + "/discover?frequency=SOMETIMES")));
        assertEquals(400, status(doPut(owner, S + "/" + id + "/membership-settings", "{\"discoverable\":true,\"publicSummary\":\"short\",\"inviteRequiresApproval\":false}")));
        assertEquals(400, status(doPut(owner, S + "/" + id + "/membership-settings", "{\"discoverable\":true,\"publicSummary\":\"A long enough public summary\",\"maxMembers\":1,\"inviteRequiresApproval\":false}")));
        assertEquals(400, status(doPost(owner, S + "/" + id + "/invite-links", "{\"expiresInDays\":0}")));
        assertEquals(400, status(doPost(owner, S + "/" + id + "/invite-links", "{\"maxUses\":1000}")));
        assertEquals(404, status(doPost(owner, S + "/invite-links/accept", "{\"code\":\"' OR 1=1 --\"}")));
        List<?> none = JsonPath.read(body(doGet(owner, S + "/discover?q=%25")), "$.items");
        assertTrue(none.isEmpty(), "% is a literal");
    }
}
