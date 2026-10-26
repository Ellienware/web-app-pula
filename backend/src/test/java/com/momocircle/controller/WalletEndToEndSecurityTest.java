package com.momocircle.controller;

import com.jayway.jsonpath.JsonPath;
import com.momocircle.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end through the REAL security filter chain and JWT filter (no mocks): authentication, ownership, cross-user access,
 * idempotency, validation, QR/payment-request rules, admin gating, rate limiting and error-body hygiene.
 * NOT RUN in the sandbox that produced it (no Maven); run with {@code mvn -Dtest=WalletEndToEndSecurityTest test}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:wallete2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "app.wallet.qr-secret=e2e-test-qr-secret-0123456789-abcdefghijklmnop"
})
class WalletEndToEndSecurityTest {

    private static final String W = "/api/money/wallet";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;

    // ------------------------------------------------------------------ fixtures

    record U(String id, String token, String name) { }

    U user(String fullName, String role) {
        String id = UUID.randomUUID().toString();
        String email = id + "@e2e.local";
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into users (id, created_at, updated_at, email, full_name, phone_number, password_hash, role, active) values (?,?,?,?,?,?,?,?,?)",
                id, now, now, email, fullName, "+2772" + (long) (Math.random() * 1e7), "x", role, true);
        return new U(id, jwt.generateToken(id, email), fullName);
    }

    U user(String fullName) { return user(fullName, "USER"); }

    static String key() { return "e2e-" + UUID.randomUUID(); }

    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, U u) {
        return u == null ? b : b.header("Authorization", "Bearer " + u.token());
    }

    MvcResult send(MockHttpServletRequestBuilder b) throws Exception { return mvc.perform(b).andReturn(); }

    MvcResult doPost(U u, String path, String json, String idemKey) throws Exception {
        MockHttpServletRequestBuilder b = auth(post(W + path).contentType(MediaType.APPLICATION_JSON).content(json), u);
        if (idemKey != null) b.header("Idempotency-Key", idemKey);
        return send(b);
    }

    MvcResult doGet(U u, String path) throws Exception { return send(auth(get(W + path), u)); }

    static String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }
    static int status(MvcResult r) { return r.getResponse().getStatus(); }
    static String json(MvcResult r, String path) throws Exception { return String.valueOf((Object) JsonPath.read(body(r), path)); }

    String walletId(U u) throws Exception { return json(doGet(u, ""), "$.walletId"); }
    String balance(U u) throws Exception { return json(doGet(u, ""), "$.balance"); }

    void fund(U u, String amount) throws Exception {
        assertEquals(201, status(doPost(u, "/demo-funding", "{\"amount\":" + amount + "}", key())));
    }

    // ------------------------------------------------------------------ authentication

    /**
     * The existing security config has no custom entry point, so Spring Security answers an unauthenticated request with 403
     * (not 401). What matters here is that it is refused and never served; the status is the platform's pre-existing behaviour.
     */
    static void assertUnauthenticated(int status) {
        assertTrue(status == 401 || status == 403, "expected the request to be refused as unauthenticated, got " + status);
    }

    @Test
    void everyWalletEndpointRequiresAuthentication() throws Exception {
        assertUnauthenticated(status(doGet(null, "")));
        assertUnauthenticated(status(doGet(null, "/transactions")));
        assertUnauthenticated(status(doPost(null, "/transfers", "{\"recipientId\":\"PW-X\",\"amount\":1}", key())));
        assertUnauthenticated(status(doPost(null, "/demo-funding", "{\"amount\":1}", key())));
        assertUnauthenticated(status(doPost(null, "/payment-requests/pay", "{}", null)));
        assertUnauthenticated(status(doGet(null, "/admin/withdrawals")));
    }

    @Test
    void garbageAndTamperedTokensAreRejected() throws Exception {
        assertUnauthenticated(status(send(get(W).header("Authorization", "Bearer not.a.jwt"))));
        U a = user("Ann Lee");
        String tampered = a.token().substring(0, a.token().length() - 3) + (a.token().endsWith("AAA") ? "BBB" : "AAA");
        assertUnauthenticated(status(send(get(W).header("Authorization", "Bearer " + tampered))));
    }

    // ------------------------------------------------------------------ ownership

    @Test
    void walletBelongsToTheTokenUserAndNeverExposesInternalIds() throws Exception {
        U a = user("Ann Lee");
        MvcResult r = doGet(a, "");
        assertEquals(200, status(r));
        assertTrue(json(r, "$.walletId").startsWith("PW-"));
        assertEquals("true", json(r, "$.simulated"));
        assertFalse(body(r).contains(a.id()), "internal user id must not appear in the response");
    }

    @Test
    void clientSuppliedOwnerFieldsNeverGrantAccessToAnotherUsersFunds() throws Exception {
        U a = user("Ann Lee"), victim = user("Vic Tim"), c = user("Cy Dube");
        fund(victim, "100.00");
        // A has no funds. Pretend to be the victim by supplying their ids in every plausible field.
        String forged = "{\"recipientId\":\"" + walletId(c) + "\",\"amount\":50.00,\"userId\":\"" + victim.id() + "\",\"ownerUserId\":\""
                + victim.id() + "\",\"fromWalletId\":\"" + walletId(victim) + "\",\"walletId\":\"" + walletId(victim) + "\"}";
        MvcResult r = doPost(a, "/transfers", forged, key());
        assertEquals(409, status(r));
        assertEquals("INSUFFICIENT_FUNDS", json(r, "$.code"));
        assertEquals("100.00", balance(victim));
        assertEquals("0.00", balance(c));
    }

    @Test
    void strangersCannotReadSomeoneElsesTransactionOrPaymentRequest() throws Exception {
        U a = user("Ann Lee"), b = user("Bo Nkosi"), stranger = user("Eve Mallory");
        fund(a, "100.00");
        MvcResult t = doPost(a, "/transfers", "{\"recipientId\":\"" + walletId(b) + "\",\"amount\":5.00}", key());
        String ref = json(t, "$.reference");
        assertEquals(200, status(doGet(b, "/transactions/" + ref)));
        assertEquals(404, status(doGet(stranger, "/transactions/" + ref)));
        assertEquals("0", json(doGet(stranger, "/transactions"), "$.totalItems"));

        MvcResult req = doPost(a, "/payment-requests", "{\"amount\":10.00,\"purpose\":\"Lunch\"}", null);
        String id = json(req, "$.requestId");
        assertEquals(200, status(doGet(a, "/payment-requests/" + id)));
        assertEquals(404, status(doGet(stranger, "/payment-requests/" + id)));
        assertEquals(404, status(doPost(stranger, "/payment-requests/" + id + "/cancel", "", null)));
    }

    @Test
    void adminEndpointsAreForbiddenToOrdinaryUsersAndOpenToAdmins() throws Exception {
        U a = user("Ann Lee"), admin = user("Ops Admin", "ADMIN");
        assertEquals(403, status(doGet(a, "/admin/withdrawals")));
        assertEquals(403, status(doPost(a, "/admin/withdrawals/SIM-ABC/complete", "", null)));
        assertEquals(403, status(doGet(a, "/admin/reconcile/" + walletId(a))));
        assertEquals(200, status(doGet(admin, "/admin/withdrawals")));
        assertEquals("true", json(doGet(admin, "/admin/reconcile/" + walletId(a)), "$.consistent"));
    }

    // ------------------------------------------------------------------ idempotency, validation, errors

    @Test
    void transferRequiresAKeyReplaysSafelyAndRejectsConflictingReuse() throws Exception {
        U a = user("Ann Lee"), b = user("Bo Nkosi");
        fund(a, "100.00");
        String body = "{\"recipientId\":\"" + walletId(b) + "\",\"amount\":10.00}";
        MvcResult noKey = doPost(a, "/transfers", body, null);
        assertEquals(400, status(noKey));
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", json(noKey, "$.code"));

        String k = key();
        MvcResult first = doPost(a, "/transfers", body, k);
        MvcResult replay = doPost(a, "/transfers", body, k);
        assertEquals(201, status(first));
        assertEquals(200, status(replay));
        assertEquals("true", replay.getResponse().getHeader("Idempotent-Replay"));
        assertEquals(json(first, "$.reference"), json(replay, "$.reference"));
        assertEquals("90.00", balance(a));
        assertEquals("10.00", balance(b));

        MvcResult conflict = doPost(a, "/transfers", "{\"recipientId\":\"" + walletId(b) + "\",\"amount\":11.00}", k);
        assertEquals(409, status(conflict));
        assertEquals("IDEMPOTENCY_CONFLICT", json(conflict, "$.code"));
        assertEquals("90.00", balance(a));
    }

    @Test
    void invalidAmountsAreRejectedBeforeAnyMoneyMoves() throws Exception {
        U a = user("Ann Lee"), b = user("Bo Nkosi");
        fund(a, "100.00");
        String to = walletId(b);
        for (String amount : new String[]{"0", "-5", "10.005", "0.001", "99999999999999.00", "\"abc\"", "null"}) {
            int s = status(doPost(a, "/transfers", "{\"recipientId\":\"" + to + "\",\"amount\":" + amount + "}", key()));
            assertEquals(400, s, "amount " + amount);
        }
        assertEquals(400, status(doPost(a, "/transfers", "{\"recipientId\":\"" + to + "\",\"amount\":1.00,\"currency\":\"USD\"}", key())));
        assertEquals("100.00", balance(a));
        assertEquals("0.00", balance(b));
    }

    @Test
    void errorBodiesAreSafeAndCarryAStableCode() throws Exception {
        U a = user("Ann Lee"), b = user("Bo Nkosi");
        MvcResult r = doPost(a, "/transfers", "{\"recipientId\":\"" + walletId(b) + "\",\"amount\":5.00}", key());
        assertEquals(409, status(r));
        assertEquals("INSUFFICIENT_FUNDS", json(r, "$.code"));
        String text = body(r).toLowerCase();
        for (String leak : new String[]{"exception", "sql", "hibernate", "stack", "at com.", a.id(), b.id()}) {
            assertFalse(text.contains(leak.toLowerCase()), "error body leaked: " + leak);
        }
        assertEquals("SELF_TRANSFER", json(doPost(a, "/transfers", "{\"recipientId\":\"" + walletId(a) + "\",\"amount\":1.00}", key()), "$.code"));
        assertEquals("RECIPIENT_NOT_FOUND", json(doPost(a, "/transfers", "{\"recipientId\":\"PW-NOPE000000\",\"amount\":1.00}", key()), "$.code"));
    }

    // ------------------------------------------------------------------ payment requests and QR

    @Test
    void qrFlowScanNeverPaysConfirmationDoesAndTamperingFails() throws Exception {
        U payee = user("Pat Dlamini"), payer = user("Quinn Zulu"), other = user("Rae Smit");
        fund(payer, "100.00");
        fund(other, "100.00");
        MvcResult created = doPost(payee, "/payment-requests", "{\"amount\":25.00,\"purpose\":\"Lunch\"}", null);
        assertEquals(201, status(created));
        String code = json(created, "$.code");
        assertTrue(code.matches("PULAPAY1\\.[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{16}"), "QR code must contain only the expected signed-token structure");

        // scanning/resolving (any number of times) moves nothing
        for (int i = 0; i < 3; i++) {
            MvcResult seen = doPost(payer, "/payment-requests/resolve", "{\"code\":\"" + code + "\"}", null);
            assertEquals(200, status(seen));
            assertEquals("OPEN", json(seen, "$.status"));
            assertEquals("25.00", json(seen, "$.amount"));
        }
        assertEquals("100.00", balance(payer));
        assertEquals("0.00", balance(payee));

        // tampered / forged codes
        String[] parts = code.split("\\.");
        String flipped = parts[0] + "." + parts[1] + "." + (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        for (String bad : new String[]{flipped, parts[1], "PULAPAY1.x.y", ""}) {
            MvcResult r = doPost(payer, "/payment-requests/pay", "{\"code\":\"" + bad + "\",\"expectedAmount\":25.00,\"expectedCurrency\":\"ZAR\"}", null);
            assertTrue(status(r) == 404 || status(r) == 400, "tampered code must fail, got " + status(r));
        }
        assertEquals("100.00", balance(payer));

        // payer must echo what they reviewed
        MvcResult changed = doPost(payer, "/payment-requests/pay", "{\"code\":\"" + code + "\",\"expectedAmount\":24.00,\"expectedCurrency\":\"ZAR\"}", null);
        assertEquals(409, status(changed));
        assertEquals("REQUEST_CHANGED", json(changed, "$.code"));

        // the requester cannot pay their own request
        assertEquals("SELF_PAYMENT", json(doPost(payee, "/payment-requests/pay", "{\"code\":\"" + code + "\",\"expectedAmount\":25.00,\"expectedCurrency\":\"ZAR\"}", null), "$.code"));

        // explicit confirmation pays exactly once
        String pay = "{\"code\":\"" + code + "\",\"expectedAmount\":25.00,\"expectedCurrency\":\"ZAR\"}";
        MvcResult paid = doPost(payer, "/payment-requests/pay", pay, null);
        assertEquals(200, status(paid));
        assertEquals("PAID", json(paid, "$.request.status"));
        assertTrue(json(paid, "$.transaction.reference").startsWith("SIM-"));
        assertEquals("75.00", balance(payer));
        assertEquals("25.00", balance(payee));
        // duplicate submission by the same payer: replay, no second debit
        MvcResult dup = doPost(payer, "/payment-requests/pay", pay, null);
        assertEquals(200, status(dup));
        assertEquals("true", json(dup, "$.replayed"));
        assertEquals("75.00", balance(payer));
        // a different payer is told it is already paid
        MvcResult late = doPost(other, "/payment-requests/pay", pay, null);
        assertEquals(409, status(late));
        assertEquals("ALREADY_PAID", json(late, "$.code"));
        assertEquals("100.00", balance(other));
    }

    @Test
    void expiredCancelledAndTargetedRequestsBehave() throws Exception {
        U payee = user("Pat Dlamini"), target = user("Quinn Zulu"), other = user("Rae Smit");
        fund(target, "100.00");
        fund(other, "100.00");

        MvcResult exp = doPost(payee, "/payment-requests", "{\"amount\":5.00,\"purpose\":\"Old\"}", null);
        jdbc.update("update wallet_payment_requests set expires_at = ? where public_ref = ?", Timestamp.from(Instant.now().minusSeconds(60)), json(exp, "$.requestId"));
        MvcResult expired = doPost(target, "/payment-requests/pay", "{\"code\":\"" + json(exp, "$.code") + "\",\"expectedAmount\":5.00,\"expectedCurrency\":\"ZAR\"}", null);
        assertEquals(410, status(expired));
        assertEquals("REQUEST_EXPIRED", json(expired, "$.code"));

        MvcResult c = doPost(payee, "/payment-requests", "{\"amount\":5.00,\"purpose\":\"Cancel me\"}", null);
        assertEquals(200, status(doPost(payee, "/payment-requests/" + json(c, "$.requestId") + "/cancel", "", null)));
        assertEquals("REQUEST_CANCELLED", json(doPost(target, "/payment-requests/pay", "{\"code\":\"" + json(c, "$.code") + "\",\"expectedAmount\":5.00,\"expectedCurrency\":\"ZAR\"}", null), "$.code"));

        MvcResult t = doPost(payee, "/payment-requests", "{\"amount\":7.00,\"purpose\":\"Just you\",\"payerRecipientId\":\"" + walletId(target) + "\"}", null);
        String tcode = json(t, "$.code");
        assertEquals(404, status(doPost(other, "/payment-requests/resolve", "{\"code\":\"" + tcode + "\"}", null)));
        assertEquals(200, status(doPost(target, "/payment-requests/resolve", "{\"code\":\"" + tcode + "\"}", null)));
        assertEquals("REJECTED", json(doPost(target, "/payment-requests/reject", "{\"code\":\"" + tcode + "\"}", null), "$.status"));
        assertEquals("100.00", balance(target));
    }

    // ------------------------------------------------------------------ abuse protection

    @Test
    void transfersAreRateLimitedPerUser() throws Exception {
        U a = user("Ann Lee"), b = user("Bo Nkosi");
        fund(a, "50.00");
        String body = "{\"recipientId\":\"" + walletId(b) + "\",\"amount\":0.01}";
        int ok = 0, limited = 0;
        for (int i = 0; i < 35; i++) {
            int s = status(doPost(a, "/transfers", body, key()));
            if (s == 201) ok++;
            else if (s == 429) limited++;
        }
        assertEquals(30, ok, "the first 30 transfers in the window succeed");
        assertEquals(5, limited, "the rest are refused with 429");
        // the limit is per user: another user is unaffected
        assertEquals(200, status(doGet(b, "")));
    }

    @Test
    void recipientLookupIsRateLimitedAndNeverRevealsPersonalData() throws Exception {
        U a = user("Ann Lee"), b = user("Bo Nkosi");
        String email = jdbc.queryForObject("select email from users where id = ?", String.class, b.id());
        MvcResult hit = doPost(a, "/recipients/lookup", "{\"identifier\":\"" + email + "\"}", null);
        assertEquals(200, status(hit));
        assertEquals("Bo N.", json(hit, "$.displayName"));
        assertFalse(body(hit).contains(email));
        assertFalse(body(hit).contains(b.id()));
        int limited = 0;
        for (int i = 0; i < 20; i++) if (status(doPost(a, "/recipients/lookup", "{\"identifier\":\"nobody" + i + "@e2e.local\"}", null)) == 429) limited++;
        assertTrue(limited > 0, "lookup must be rate limited");
    }
}
