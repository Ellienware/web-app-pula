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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Business wallets end to end through the REAL security filter chain and JWT filter: membership and role gating, cross-user and
 * cross-business access, forged ids, delegated access, and customers paying a business by QR.
 * NOT RUN in the sandbox that produced it (no Maven); run with {@code mvn -Dtest=BusinessWalletEndToEndTest test}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bizwallete2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "app.wallet.qr-secret=e2e-test-qr-secret-0123456789-abcdefghijklmnop"
})
class BusinessWalletEndToEndTest {

    private static final String P = "/api/money/wallet";
    private static final String B = "/api/money/business-wallets";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;

    record U(String id, String token, String email) { }

    U user(String fullName) {
        String id = UUID.randomUUID().toString();
        String email = id + "@biz.local";
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into users (id, created_at, updated_at, email, full_name, phone_number, password_hash, role, active) values (?,?,?,?,?,?,?,?,?)",
                id, now, now, email, fullName, "+2773" + (long) (Math.random() * 1e7), "x", "USER", true);
        return new U(id, jwt.generateToken(id, email), email);
    }

    void business(U owner, String name) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into businesses (id, created_at, updated_at, owner_id, name, category) values (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), now, now, owner.id(), name, "Food");
    }

    static String key() { return "biz-" + UUID.randomUUID(); }

    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, U u) { return u == null ? b : b.header("Authorization", "Bearer " + u.token()); }

    MvcResult send(MockHttpServletRequestBuilder b) throws Exception { return mvc.perform(b).andReturn(); }

    MvcResult doPost(U u, String path, String json, String idem) throws Exception {
        MockHttpServletRequestBuilder b = auth(post(path).contentType(MediaType.APPLICATION_JSON).content(json), u);
        if (idem != null) b.header("Idempotency-Key", idem);
        return send(b);
    }
    MvcResult doPut(U u, String path, String json) throws Exception { return send(auth(put(path).contentType(MediaType.APPLICATION_JSON).content(json), u)); }
    MvcResult doGet(U u, String path) throws Exception { return send(auth(get(path), u)); }
    MvcResult doDelete(U u, String path) throws Exception { return send(auth(delete(path), u)); }

    static String body(MvcResult r) throws Exception { return r.getResponse().getContentAsString(); }
    static int status(MvcResult r) { return r.getResponse().getStatus(); }
    static String json(MvcResult r, String path) throws Exception { return String.valueOf((Object) JsonPath.read(body(r), path)); }

    String bwId(U owner) throws Exception { return json(doGet(owner, B), "$[0].walletId"); }
    String pwId(U u) throws Exception { return json(doGet(u, P), "$.walletId"); }
    String personalBalance(U u) throws Exception { return json(doGet(u, P), "$.balance"); }
    String businessBalance(U u, String bw) throws Exception { return json(doGet(u, B + "/" + bw), "$.wallet.balance"); }

    void fundBusiness(U u, String bw, String amount) throws Exception {
        assertEquals(201, status(doPost(u, B + "/" + bw + "/demo-funding", "{\"amount\":" + amount + "}", key())));
    }

    void addMember(U owner, String bw, U member, String role) throws Exception {
        assertEquals(201, status(doPost(owner, B + "/" + bw + "/members", "{\"identifier\":\"" + member.email() + "\",\"role\":\"" + role + "\"}", null)));
    }

    @Test
    void everyBusinessEndpointRequiresAuthentication() throws Exception {
        for (MvcResult r : new MvcResult[]{doGet(null, B), doGet(null, B + "/BW-X"), doGet(null, B + "/BW-X/transactions"),
                doPost(null, B + "/BW-X/transfers", "{}", key()), doGet(null, B + "/BW-X/members")}) {
            int s = status(r);
            assertTrue(s == 401 || s == 403, "expected refusal, got " + s);
        }
    }

    @Test
    void theOwnerSeesTheirBusinessWalletAndStrangersGetNotFoundOnEveryEndpoint() throws Exception {
        U owner = user("Olu Bakker"), stranger = user("Eve Mallory");
        business(owner, "Olu's Kitchen");
        String bw = bwId(owner);
        MvcResult mine = doGet(owner, B + "/" + bw);
        assertEquals(200, status(mine));
        assertEquals("OWNER", json(mine, "$.role"));
        assertEquals("Olu's Kitchen", json(mine, "$.businessName"));
        assertFalse(body(mine).contains(owner.id()), "no internal user id in the response");

        assertEquals("[]", body(doGet(stranger, B)));
        String[][] calls = {{"GET", ""}, {"GET", "/transactions"}, {"GET", "/payment-requests"}, {"GET", "/members"}};
        for (String[] c : calls) assertEquals(404, status(doGet(stranger, B + "/" + bw + c[1])), c[1]);
        assertEquals(404, status(doPost(stranger, B + "/" + bw + "/demo-funding", "{\"amount\":10}", key())));
        assertEquals(404, status(doPost(stranger, B + "/" + bw + "/transfers", "{\"recipientId\":\"" + pwId(owner) + "\",\"amount\":1}", key())));
        assertEquals(404, status(doPost(stranger, B + "/" + bw + "/payment-requests", "{\"amount\":5,\"purpose\":\"x\"}", null)));
        assertEquals(404, status(doPost(stranger, B + "/" + bw + "/members", "{\"identifier\":\"" + stranger.email() + "\",\"role\":\"FINANCE\"}", null)));
    }

    @Test
    void theOwnersPersonalSessionCannotSpendTheBusinessWalletAndViceVersa() throws Exception {
        U owner = user("Olu Bakker"), supplier = user("Sup Plier");
        business(owner, "Olu's Kitchen");
        String bw = bwId(owner);
        fundBusiness(owner, bw, "100.00");
        // personal endpoint with every forged hint: it can only ever use the PERSONAL wallet, which is empty
        MvcResult r = doPost(owner, P + "/transfers", "{\"recipientId\":\"" + pwId(supplier) + "\",\"amount\":50.00,\"walletId\":\"" + bw
                + "\",\"businessId\":\"" + bw + "\",\"ownerUserId\":\"" + owner.id() + "\"}", key());
        assertEquals(409, status(r));
        assertEquals("INSUFFICIENT_FUNDS", json(r, "$.code"));
        assertEquals("100.00", businessBalance(owner, bw));
        assertEquals("0.00", personalBalance(supplier));
        // and the business endpoint cannot be pointed at a personal wallet id
        assertEquals(404, status(doGet(owner, B + "/" + pwId(owner))));
    }

    @Test
    void rolesAreEnforcedOverHttp() throws Exception {
        U owner = user("Olu Bakker"), finance = user("Fin Ance"), staff = user("Sta Ff"), supplier = user("Sup Plier");
        business(owner, "Olu's Kitchen");
        String bw = bwId(owner);
        addMember(owner, bw, finance, "FINANCE");
        addMember(owner, bw, staff, "STAFF");
        fundBusiness(owner, bw, "200.00");
        String xfer = "{\"recipientId\":\"" + pwId(supplier) + "\",\"amount\":10.00}";

        // STAFF: can view and request; cannot send, withdraw, fund or manage
        assertEquals(200, status(doGet(staff, B + "/" + bw)));
        assertEquals(201, status(doPost(staff, B + "/" + bw + "/payment-requests", "{\"amount\":5,\"purpose\":\"Lunch\"}", null)));
        for (MvcResult r : new MvcResult[]{doPost(staff, B + "/" + bw + "/transfers", xfer, key()),
                doPost(staff, B + "/" + bw + "/withdrawals", "{\"amount\":1}", key()),
                doPost(staff, B + "/" + bw + "/demo-funding", "{\"amount\":1}", key()),
                doGet(staff, B + "/" + bw + "/members"),
                doPost(staff, B + "/" + bw + "/members", "{\"identifier\":\"" + staff.email() + "\",\"role\":\"FINANCE\"}", null)}) {
            assertEquals(403, status(r));
            assertEquals("FORBIDDEN_ROLE", json(r, "$.code"));
        }
        // FINANCE: can send and withdraw; cannot manage members
        assertEquals(201, status(doPost(finance, B + "/" + bw + "/transfers", xfer, key())));
        assertEquals(201, status(doPost(finance, B + "/" + bw + "/withdrawals", "{\"amount\":5.00}", key())));
        assertEquals(403, status(doGet(finance, B + "/" + bw + "/members")));
        assertEquals("185.00", businessBalance(owner, bw));
        assertEquals("10.00", personalBalance(supplier));
    }

    @Test
    void ownerManagesMembersAndRemovalRevokesAccessImmediately() throws Exception {
        U owner = user("Olu Bakker"), member = user("Mem Ber");
        business(owner, "Olu's Kitchen");
        String bw = bwId(owner);
        addMember(owner, bw, member, "FINANCE");
        assertEquals(200, status(doGet(member, B + "/" + bw)));
        assertEquals(1, ((java.util.List<?>) JsonPath.read(body(doGet(member, B)), "$")).size());
        String memberId = pwId(member);
        assertEquals("STAFF", json(doPut(owner, B + "/" + bw + "/members/" + memberId, "{\"role\":\"STAFF\"}"), "$.role"));
        assertEquals(403, status(doPost(member, B + "/" + bw + "/withdrawals", "{\"amount\":1}", key())));
        assertEquals(409, status(doPost(owner, B + "/" + bw + "/members", "{\"identifier\":\"" + member.email() + "\",\"role\":\"STAFF\"}", null)));
        assertEquals(400, status(doPost(owner, B + "/" + bw + "/members", "{\"identifier\":\"" + owner.email() + "\",\"role\":\"FINANCE\"}", null)));
        assertEquals(400, status(doPost(owner, B + "/" + bw + "/members", "{\"identifier\":\"" + user("New Guy").email() + "\",\"role\":\"OWNER\"}", null)));
        assertEquals(204, status(doDelete(owner, B + "/" + bw + "/members/" + memberId)));
        assertEquals(404, status(doGet(member, B + "/" + bw)));
        assertEquals("[]", body(doGet(member, B)));
    }

    @Test
    void membersOfOneBusinessCannotReachAnother() throws Exception {
        U ownerA = user("Aa Owner"), ownerB = user("Bb Owner"), memberA = user("Mem Ber");
        business(ownerA, "Shop A");
        business(ownerB, "Shop B");
        String a = bwId(ownerA), b = bwId(ownerB);
        addMember(ownerA, a, memberA, "FINANCE");
        assertEquals(200, status(doGet(memberA, B + "/" + a)));
        assertEquals(404, status(doGet(memberA, B + "/" + b)));
        assertEquals(404, status(doPost(memberA, B + "/" + b + "/transfers", "{\"recipientId\":\"" + pwId(ownerA) + "\",\"amount\":1}", key())));
    }

    @Test
    void customersPayABusinessByQrAndOnlyTheBusinessWalletIsCredited() throws Exception {
        U owner = user("Olu Bakker"), staff = user("Sta Ff"), customer = user("Cus Tomer");
        business(owner, "Olu's Kitchen");
        String bw = bwId(owner);
        addMember(owner, bw, staff, "STAFF");
        assertEquals(201, status(doPost(customer, P + "/demo-funding", "{\"amount\":100.00}", key())));

        MvcResult created = doPost(staff, B + "/" + bw + "/payment-requests", "{\"amount\":35.00,\"purpose\":\"Lunch order\"}", null);
        assertEquals(201, status(created));
        String code = json(created, "$.code");
        assertEquals("Olu's Kitchen", json(created, "$.requesterName"));

        MvcResult seen = doPost(customer, P + "/payment-requests/resolve", "{\"code\":\"" + code + "\"}", null);
        assertEquals(200, status(seen));
        assertEquals("Olu's Kitchen", json(seen, "$.requesterName"));
        assertEquals("true", json(seen, "$.canPay"));
        assertEquals("0.00", businessBalance(owner, bw));

        String pay = "{\"code\":\"" + code + "\",\"expectedAmount\":35.00,\"expectedCurrency\":\"ZAR\"}";
        MvcResult paid = doPost(customer, P + "/payment-requests/pay", pay, null);
        assertEquals(200, status(paid));
        assertEquals("PAID", json(paid, "$.request.status"));
        assertEquals("35.00", businessBalance(owner, bw));
        assertEquals("65.00", personalBalance(customer));
        assertEquals("0.00", personalBalance(owner), "the owner's personal wallet is not credited");
        assertEquals("true", json(doPost(customer, P + "/payment-requests/pay", pay, null), "$.replayed"));
        assertEquals("35.00", businessBalance(owner, bw));

        // staff and owner cannot pay their own business's request from a personal wallet
        assertEquals(400, status(doPost(owner, P + "/payment-requests/pay", pay, null)));
        MvcResult listed = doGet(staff, B + "/" + bw + "/payment-requests");
        assertEquals("PAID", json(listed, "$.items[0].status"));
        assertTrue(json(listed, "$.items[0].transactionReference").startsWith("SIM-"));
        // the business's transaction history shows the incoming payment
        MvcResult tx = doGet(owner, B + "/" + bw + "/transactions");
        assertEquals("IN", json(tx, "$.items[0].direction"));
        assertEquals("Cus T.", json(tx, "$.items[0].counterparty"));
    }

    @Test
    void businessTransfersAreIdempotentAndRateLimited() throws Exception {
        U owner = user("Olu Bakker"), supplier = user("Sup Plier");
        business(owner, "Olu's Kitchen");
        String bw = bwId(owner);
        fundBusiness(owner, bw, "100.00");
        String body = "{\"recipientId\":\"" + pwId(supplier) + "\",\"amount\":5.00}";
        String k = key();
        assertEquals(201, status(doPost(owner, B + "/" + bw + "/transfers", body, k)));
        MvcResult replay = doPost(owner, B + "/" + bw + "/transfers", body, k);
        assertEquals(200, status(replay));
        assertEquals("true", replay.getResponse().getHeader("Idempotent-Replay"));
        assertEquals(409, status(doPost(owner, B + "/" + bw + "/transfers", "{\"recipientId\":\"" + pwId(supplier) + "\",\"amount\":6.00}", k)));
        assertEquals("95.00", businessBalance(owner, bw));
        assertEquals(400, status(doPost(owner, B + "/" + bw + "/transfers", body, null)));

        String tiny = "{\"recipientId\":\"" + pwId(supplier) + "\",\"amount\":0.01}";
        int limited = 0;
        for (int i = 0; i < 35; i++) if (status(doPost(owner, B + "/" + bw + "/transfers", tiny, key())) == 429) limited++;
        assertTrue(limited >= 4, "business transfers must be rate limited per user, saw " + limited);
    }
}
