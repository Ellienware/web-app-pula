package com.momocircle.service;

import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.StokvelProfile;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.CircleType;
import com.momocircle.domain.enums.JoinRequestStatus;
import com.momocircle.domain.enums.RecurringFrequency;
import com.momocircle.domain.enums.StokvelType;
import com.momocircle.dto.CircleDtos.CreateCircleRequest;
import com.momocircle.dto.StokvelMembershipDtos.AcceptInviteResult;
import com.momocircle.dto.StokvelMembershipDtos.CreatedInviteLink;
import com.momocircle.dto.StokvelMembershipDtos.DiscoverableStokvel;
import com.momocircle.dto.StokvelMembershipDtos.JoinRequestView;
import com.momocircle.dto.StokvelMembershipDtos.PageView;
import com.momocircle.dto.StokvelMembershipDtos.UpdateMembershipSettings;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.CircleMembershipRepository;
import com.momocircle.repository.CircleRepository;
import com.momocircle.repository.StokvelProfileRepository;
import com.momocircle.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Stokvel discovery, invite links and join requests against a real database (H2 in PostgreSQL mode, Flyway V1..V45), with no
 * surrounding test transaction so locks and constraints behave as in production. NOT RUN in the sandbox that produced it (no
 * Maven); run with {@code mvn -Dtest=StokvelMembershipH2Test test}. Rotation slot syncing is mocked and only verified to be called.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StokvelMembershipService.class, StokvelDiscoveryQuery.class, CircleService.class, ActivityService.class,
        NotificationService.class, FinancialValidationService.class, com.momocircle.config.CurrencyProperties.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StokvelMembershipH2Test {

    @Autowired StokvelMembershipService service;
    @Autowired CircleService circleService;
    @Autowired CircleRepository circles;
    @Autowired CircleMembershipRepository memberships;
    @Autowired StokvelProfileRepository profiles;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockBean StokvelRotationService rotation;

    // ------------------------------------------------------------------ fixtures

    User user(String fullName) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into users (id, created_at, updated_at, email, full_name, phone_number, password_hash, role, active) values (?,?,?,?,?,?,?,?,?)",
                id, now, now, id + "@sv.local", fullName, "+2774" + (long) (Math.random() * 1e7), "x", "USER", true);
        return users.findById(id).orElseThrow();
    }

    /** A new stokvel owned by {@code owner}. */
    String stokvel(User owner, StokvelType type, String contribution) {
        String circleId = circleService.createForStokvel(owner, new CreateCircleRequest("Umoja " + UUID.randomUUID().toString().substring(0, 6),
                "PRIVATE description for members only", CircleType.STOKVEL, "ZAR", null)).id();
        StokvelProfile p = new StokvelProfile();
        p.setCircle(circles.findById(circleId).orElseThrow());
        p.setStokvelType(type);
        p.setContributionAmount(new BigDecimal(contribution));
        p.setContributionFrequency(RecurringFrequency.MONTHLY);
        p.setStartDate(LocalDate.now());
        p.setApprovalRule(com.momocircle.domain.enums.ApprovalRuleType.SINGLE);
        profiles.save(p);
        return circleId;
    }

    String stokvel(User owner) { return stokvel(owner, StokvelType.GENERAL, "200.00"); }

    void open(User owner, String circleId, String summary, String area, Integer max, boolean inviteNeedsApproval) {
        service.updateSettings(circleId, owner, new UpdateMembershipSettings(true, area, summary, "Must live in Soweto and attend meetings", max, inviteNeedsApproval));
    }

    void makePrivate(User owner, String circleId) {
        service.updateSettings(circleId, owner, new UpdateMembershipSettings(false, null, null, null, null, false));
    }

    void addAs(String circleId, User u, CircleRole role) {
        CircleMembership m = new CircleMembership();
        m.setCircle(circles.findById(circleId).orElseThrow());
        m.setUser(u);
        m.setRole(role);
        memberships.save(m);
    }

    boolean isMember(String circleId, User u) { return memberships.findByCircleIdAndUserId(circleId, u.getId()).isPresent(); }

    static String statusOf(Runnable r) {
        ApiException e = assertThrows(ApiException.class, r::run);
        return e.getStatus().name();
    }

    static String messageOf(Runnable r) { return assertThrows(ApiException.class, r::run).getMessage(); }

    static final StokvelDiscoveryQuery.Filter ANY = new StokvelDiscoveryQuery.Filter(null, null, null, null, null, null);

    <T> List<Object> concurrently(List<Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> fs = new ArrayList<>();
        for (Callable<T> job : jobs) {
            fs.add(pool.submit(() -> {
                go.await();
                try { return job.call(); }
                catch (ApiException e) { return e.getStatus().name(); }
            }));
        }
        go.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : fs) out.add(f.get(120, TimeUnit.SECONDS));
        pool.shutdown();
        return out;
    }

    // ------------------------------------------------------------------ privacy and discovery

    @Test
    void aNewStokvelIsPrivateAndInvisibleToEveryoneOutsideIt() {
        User owner = user("Thandi Mokoena"), outsider = user("Zed Outsider");
        String id = stokvel(owner);
        assertEquals(0, service.discover(outsider, ANY, 0, 50).items().stream().filter(s -> s.circleId().equals(id)).count());
        assertEquals("NOT_FOUND", statusOf(() -> service.publicView(id, outsider)));
        assertEquals("NOT_FOUND", statusOf(() -> service.requestToJoin(id, outsider, "please")), "private stays private, same as unknown");
        assertEquals("NOT_FOUND", statusOf(() -> service.requestToJoin(UUID.randomUUID().toString(), outsider, "please")));
        assertEquals("FORBIDDEN", statusOf(() -> circleService.requireMembership(id, outsider)), "no access to private data");
    }

    @Test
    void discoveryShowsOnlyThePublicCardAndNeverPrivateOrFinancialData() {
        User owner = user("Thandi Mokoena"), viewer = user("Vee Viewer");
        String id = stokvel(owner);
        open(owner, id, "Monthly savings pool for Soweto neighbours", "Soweto", 20, false);
        DiscoverableStokvel card = service.discover(viewer, ANY, 0, 50).items().stream().filter(s -> s.circleId().equals(id)).findFirst().orElseThrow();
        assertEquals("Monthly savings pool for Soweto neighbours", card.publicSummary());
        assertFalse(card.publicSummary().contains("PRIVATE"), "the private description is never exposed");
        assertEquals("Thandi M.", card.organiser(), "organiser is masked");
        assertEquals("Soweto", card.area());
        assertEquals(1, card.memberCount());
        assertEquals(19, card.spotsLeft());
        assertFalse(card.full());
        assertEquals("NONE", card.joinState());
        assertTrue(card.notice().contains("does not hold"));
        assertEquals(0, new BigDecimal("200.00").compareTo(card.contributionAmount()));
        String json = card.toString();
        assertFalse(json.contains(owner.getEmail()) || json.contains(owner.getId()), "no contact details or user ids");
    }

    @Test
    void membersDoNotSeeTheirOwnStokvelInDiscoverAndPrivateStokvelsVanishWhenMadePrivate() {
        User owner = user("Thandi Mokoena"), member = user("Mia Member"), other = user("Oli Other");
        String id = stokvel(owner);
        addAs(id, member, CircleRole.MEMBER);
        open(owner, id, "Monthly savings pool for neighbours", null, null, false);
        assertEquals(0, service.discover(member, ANY, 0, 50).items().stream().filter(s -> s.circleId().equals(id)).count());
        assertEquals(1, service.discover(other, ANY, 0, 50).items().stream().filter(s -> s.circleId().equals(id)).count());
        makePrivate(owner, id);
        assertEquals(0, service.discover(other, ANY, 0, 50).items().stream().filter(s -> s.circleId().equals(id)).count());
    }

    @Test
    void searchAndFiltersWorkAndWildcardsInSearchTextAreLiteral() {
        User owner = user("Thandi Mokoena"), viewer = user("Vee Viewer");
        String a = stokvel(owner, StokvelType.GENERAL, "100.00");
        String b = stokvel(owner, StokvelType.ROTATIONAL, "500.00");
        open(owner, a, "Grocery savings club for families", "Soweto", null, false);
        open(owner, b, "Rotating payout circle for traders", "Tembisa", null, false);

        assertEquals(List.of(a), ids(service.discover(viewer, new StokvelDiscoveryQuery.Filter("grocery", null, null, null, null, null), 0, 50), a, b));
        assertEquals(List.of(b), ids(service.discover(viewer, new StokvelDiscoveryQuery.Filter(null, StokvelType.ROTATIONAL, null, null, null, null), 0, 50), a, b));
        assertEquals(List.of(b), ids(service.discover(viewer, new StokvelDiscoveryQuery.Filter(null, null, null, new BigDecimal("300"), null, null), 0, 50), a, b));
        assertEquals(List.of(a), ids(service.discover(viewer, new StokvelDiscoveryQuery.Filter(null, null, null, null, new BigDecimal("300"), null), 0, 50), a, b));
        assertEquals(List.of(b), ids(service.discover(viewer, new StokvelDiscoveryQuery.Filter(null, null, null, null, null, "tembi"), 0, 50), a, b));
        assertEquals(List.of(), ids(service.discover(viewer, new StokvelDiscoveryQuery.Filter("%", null, null, null, null, null), 0, 50), a, b), "% is literal, not a wildcard");
        assertEquals(List.of(), ids(service.discover(viewer, new StokvelDiscoveryQuery.Filter("_", null, null, null, null, null), 0, 50), a, b));
        PageView<DiscoverableStokvel> page = service.discover(viewer, ANY, 0, 1);
        assertEquals(1, page.items().size());
        assertTrue(page.totalItems() >= 2);
    }

    private List<String> ids(PageView<DiscoverableStokvel> page, String... wanted) {
        List<String> w = List.of(wanted);
        return page.items().stream().map(DiscoverableStokvel::circleId).filter(w::contains).sorted().toList();
    }

    // ------------------------------------------------------------------ settings authorisation

    @Test
    void onlyOwnersAndAdminsCanChangeDiscoverySettingsAndTheyAreValidated() {
        User owner = user("Thandi Mokoena"), admin = user("Ada Admin"), member = user("Mia Member"), outsider = user("Zed Outsider");
        String id = stokvel(owner);
        addAs(id, admin, CircleRole.ADMIN);
        addAs(id, member, CircleRole.MEMBER);
        UpdateMembershipSettings ok = new UpdateMembershipSettings(true, "Soweto", "Monthly savings pool for neighbours", null, 10, false);
        assertEquals("FORBIDDEN", statusOf(() -> service.updateSettings(id, member, ok)));
        assertEquals("FORBIDDEN", statusOf(() -> service.updateSettings(id, outsider, ok)));
        assertEquals("FORBIDDEN", statusOf(() -> service.settings(id, member)));
        assertTrue(service.updateSettings(id, admin, ok).discoverable());
        assertEquals("BAD_REQUEST", statusOf(() -> service.updateSettings(id, owner, new UpdateMembershipSettings(true, null, "short", null, null, false))));
        assertEquals("BAD_REQUEST", statusOf(() -> service.updateSettings(id, owner, new UpdateMembershipSettings(true, null, "A long enough summary here", null, 2, false))),
                "the limit cannot be below the current 3 members");
    }

    // ------------------------------------------------------------------ join requests

    @Test
    void requestPendingThenApprovedAddsAPlainMemberExactlyOnce() {
        User owner = user("Thandi Mokoena"), applicant = user("Ann Applicant");
        String id = stokvel(owner, StokvelType.ROTATIONAL, "300.00");
        open(owner, id, "Rotating payout circle for traders", null, null, false);

        JoinRequestView r = service.requestToJoin(id, applicant, "I trade at the Soweto market");
        assertEquals("PENDING", r.status());
        assertTrue(r.canCancel());
        assertFalse(isMember(id, applicant), "a request alone adds nobody");
        assertEquals("CONFLICT", statusOf(() -> service.requestToJoin(id, applicant, "again")), "duplicate request");
        assertEquals("PENDING", service.discover(applicant, ANY, 0, 50).items().stream().filter(s -> s.circleId().equals(id)).findFirst().orElseThrow().joinState());
        assertEquals(1, service.listRequests(id, owner, JoinRequestStatus.PENDING).size());

        JoinRequestView approved = service.approve(id, r.requestId(), owner, "Welcome");
        assertEquals("APPROVED", approved.status());
        assertEquals("Thandi Mokoena", approved.decidedByName());
        assertTrue(isMember(id, applicant));
        assertEquals(CircleRole.MEMBER, memberships.findByCircleIdAndUserId(id, applicant.getId()).orElseThrow().getRole());
        verify(rotation, atLeastOnce()).syncSlots(argThat(p ->
        p != null && p.getCircle().getId().equals(id)));
        assertEquals("CONFLICT", statusOf(() -> service.approve(id, r.requestId(), owner, null)), "cannot approve twice");
        assertEquals("CONFLICT", statusOf(() -> service.requestToJoin(id, applicant, "again")), "already a member");
        assertTrue(circleService.requireMembership(id, applicant) != null, "the new member can use the stokvel");
        assertEquals(2, memberships.countByCircleId(id), "no duplicate membership");
    }

    @Test
    void anAdminCanDecideButAMemberOrOutsiderCannotAndRequestsStayInsideTheirStokvel() {
        User owner = user("Thandi Mokoena"), admin = user("Ada Admin"), member = user("Mia Member"), applicant = user("Ann Applicant"), outsider = user("Zed Outsider");
        String id = stokvel(owner), other = stokvel(owner);
        addAs(id, admin, CircleRole.ADMIN);
        addAs(id, member, CircleRole.MEMBER);
        open(owner, id, "Monthly savings pool for neighbours", null, null, false);
        open(owner, other, "Another group entirely here", null, null, false);
        JoinRequestView r = service.requestToJoin(id, applicant, null);

        assertEquals("FORBIDDEN", statusOf(() -> service.approve(id, r.requestId(), member, null)));
        assertEquals("FORBIDDEN", statusOf(() -> service.approve(id, r.requestId(), outsider, null)));
        assertEquals("FORBIDDEN", statusOf(() -> service.reject(id, r.requestId(), member, null)));
        assertEquals("FORBIDDEN", statusOf(() -> service.listRequests(id, member, null)));
        assertEquals("FORBIDDEN", statusOf(() -> service.listRequests(id, outsider, null)));
        assertEquals("FORBIDDEN", statusOf(() -> service.approve(id, r.requestId(), applicant, null)), "applicants cannot approve themselves");
        assertEquals("NOT_FOUND", statusOf(() -> service.approve(other, r.requestId(), owner, null)), "a request from another stokvel is not found");
        assertFalse(isMember(id, applicant));
        assertEquals("APPROVED", service.approve(id, r.requestId(), admin, null).status());
        assertTrue(isMember(id, applicant));
    }

    @Test
    void rejectionAndCancellationAddNobodyAndTheCooldownAppliesOnlyAfterRejection() {
        User owner = user("Thandi Mokoena"), a = user("Ann Applicant"), b = user("Bo Applicant"), stranger = user("Eve Stranger");
        String id = stokvel(owner);
        open(owner, id, "Monthly savings pool for neighbours", null, null, false);

        JoinRequestView ra = service.requestToJoin(id, a, null);
        assertEquals("NOT_FOUND", statusOf(() -> service.cancel(id, ra.requestId(), stranger)), "someone else's request is not found");
        assertEquals("CANCELLED", service.cancel(id, ra.requestId(), a).status());
        assertEquals("CONFLICT", statusOf(() -> service.cancel(id, ra.requestId(), a)), "already final");
        assertEquals("CONFLICT", statusOf(() -> service.approve(id, ra.requestId(), owner, null)), "a cancelled request cannot be approved");
        assertEquals("PENDING", service.requestToJoin(id, a, null).status(), "a cancelled request can be re-submitted at once");

        JoinRequestView rb = service.requestToJoin(id, b, null);
        assertEquals("REJECTED", service.reject(id, rb.requestId(), owner, "Not in the area").status());
        assertFalse(isMember(id, b));
        assertEquals("TOO_MANY_REQUESTS", statusOf(() -> service.requestToJoin(id, b, null)), "cooldown after a rejection");
        assertEquals("CONFLICT", statusOf(() -> service.cancel(id, rb.requestId(), b)));
        assertEquals(3, service.myRequests(a).size() + service.myRequests(b).size() + service.myRequests(stranger).size());
    }

    @Test
    void capacityIsEnforcedAtRequestAndAtApproval() {
        User owner = user("Thandi Mokoena"), m1 = user("Mia Member"), a = user("Ann Applicant"), b = user("Bo Applicant"), c = user("Cy Applicant");
        String id = stokvel(owner);
        addAs(id, m1, CircleRole.MEMBER);
        open(owner, id, "Small group of three people", null, 3, false);   // owner + m1 = 2 of 3
        JoinRequestView ra = service.requestToJoin(id, a, null);
        JoinRequestView rb = service.requestToJoin(id, b, null);           // both fit while pending: only members count
        assertEquals("APPROVED", service.approve(id, ra.requestId(), owner, null).status());
        assertEquals("CONFLICT", statusOf(() -> service.approve(id, rb.requestId(), owner, null)), "full at approval time");
        assertEquals("This stokvel is full", messageOf(() -> service.requestToJoin(id, c, null)));
        assertEquals(3, memberships.countByCircleId(id));
        assertEquals("PENDING", service.listRequests(id, owner, JoinRequestStatus.PENDING).get(0).status(), "the unapproved request stays pending");
        assertEquals("BAD_REQUEST", statusOf(() -> service.updateSettings(id, owner, new UpdateMembershipSettings(true, null, "Small group of three people", null, 2, false))),
                "limit cannot drop below current members");
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentRequestsFromTheSamePersonCreateExactlyOne() throws Exception {
        User owner = user("Thandi Mokoena"), a = user("Ann Applicant");
        String id = stokvel(owner);
        open(owner, id, "Monthly savings pool for neighbours", null, null, false);
        List<Callable<Object>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) jobs.add(() -> service.requestToJoin(id, a, null));
        List<Object> results = concurrently(jobs);
        assertEquals(1, results.stream().filter(r -> r instanceof JoinRequestView).count(), results.toString());
        assertEquals(1, service.listRequests(id, owner, JoinRequestStatus.PENDING).size());
    }

    @Test
    void concurrentApprovalsOfTheSameRequestApproveOnceAndAddOneMember() throws Exception {
        User owner = user("Thandi Mokoena"), admin = user("Ada Admin"), a = user("Ann Applicant");
        String id = stokvel(owner);
        addAs(id, admin, CircleRole.ADMIN);
        open(owner, id, "Monthly savings pool for neighbours", null, null, false);
        JoinRequestView r = service.requestToJoin(id, a, null);
        List<Callable<Object>> jobs = new ArrayList<>();
        for (int i = 0; i < 6; i++) { User who = i % 2 == 0 ? owner : admin; jobs.add(() -> service.approve(id, r.requestId(), who, null)); }
        List<Object> results = concurrently(jobs);
        assertEquals(1, results.stream().filter(x -> x instanceof JoinRequestView).count(), results.toString());
        assertEquals(3, memberships.countByCircleId(id));
    }

    @Test
    void concurrentApprovalsNeverExceedTheMemberLimit() throws Exception {
        User owner = user("Thandi Mokoena");
        String id = stokvel(owner);
        open(owner, id, "Limited group of four people", null, 4, false);   // 3 spots
        List<JoinRequestView> reqs = new ArrayList<>();
        for (int i = 0; i < 6; i++) reqs.add(service.requestToJoin(id, user("Applicant " + i), null));
        List<Callable<Object>> jobs = new ArrayList<>();
        for (JoinRequestView r : reqs) jobs.add(() -> service.approve(id, r.requestId(), owner, null));
        List<Object> results = concurrently(jobs);
        assertEquals(3, results.stream().filter(x -> x instanceof JoinRequestView).count(), results.toString());
        assertEquals(4, memberships.countByCircleId(id));
    }

    // ------------------------------------------------------------------ invite links

    @Test
    void onlyAdminsCreateInviteLinksTheCodeIsShownOnceAndNeverListed() {
        User owner = user("Thandi Mokoena"), member = user("Mia Member"), outsider = user("Zed Outsider");
        String id = stokvel(owner);
        addAs(id, member, CircleRole.MEMBER);
        assertEquals("FORBIDDEN", statusOf(() -> service.createLink(id, member, null, null)));
        assertEquals("FORBIDDEN", statusOf(() -> service.createLink(id, outsider, null, null)));
        assertEquals("FORBIDDEN", statusOf(() -> service.listLinks(id, member)));
        CreatedInviteLink made = service.createLink(id, owner, 7, 5);
        assertTrue(made.code().matches("[0-9A-Z]{4}(-[0-9A-Z]{4}){3}"));
        assertTrue(made.notice().contains("only once"));
        assertEquals("ACTIVE", made.link().status());
        assertEquals(5, made.link().maxUses());
        var listed = service.listLinks(id, owner);
        assertEquals(1, listed.size());
        assertFalse(listed.toString().contains(made.code().replace("-", "")), "the code is not retrievable after creation");
        assertEquals(StokvelInviteCodes.hint(StokvelInviteCodes.normalise(made.code()).orElseThrow()), listed.get(0).codeHint());
        assertEquals(1, jdbc.queryForObject("select count(*) from stokvel_invite_links where code_hash = ?", Integer.class,
                StokvelInviteCodes.hash(StokvelInviteCodes.normalise(made.code()).orElseThrow())), "only the hash is stored");
        assertEquals(0, jdbc.queryForObject("select count(*) from stokvel_invite_links where code_hash = ?", Integer.class, made.code()));
    }

    @Test
    void acceptingAnInviteJoinsAPrivateStokvelOnceAndIsIdempotent() {
        User owner = user("Thandi Mokoena"), friend = user("Fay Friend");
        String id = stokvel(owner, StokvelType.ROTATIONAL, "300.00");          // private: not discoverable
        CreatedInviteLink made = service.createLink(id, owner, 7, 3);
        var preview = service.preview(made.code(), friend);
        assertEquals("Thandi M.", preview.organiser());
        assertFalse(preview.alreadyMember());
        assertNull(preview.requirements());
        assertFalse(preview.toString().contains(owner.getEmail()));
        assertFalse(isMember(id, friend), "previewing adds nobody");

        AcceptInviteResult first = service.accept(made.code(), friend);
        assertEquals("JOINED", first.outcome());
        assertEquals(id, first.stokvelId());
        assertTrue(isMember(id, friend));
        assertEquals(CircleRole.MEMBER, memberships.findByCircleIdAndUserId(id, friend.getId()).orElseThrow().getRole());
        verify(rotation, atLeastOnce()).syncSlots(argThat(p ->
        p != null && p.getCircle().getId().equals(id)));
        assertEquals("ALREADY_MEMBER", service.accept(made.code(), friend).outcome(), "a retry is harmless");
        assertEquals(1, service.listLinks(id, owner).get(0).uses(), "the retry did not consume another use");
        assertEquals(2, memberships.countByCircleId(id));
    }

    @Test
    void invalidExpiredRevokedAndExhaustedInvitesAllLookTheSameAndAddNobody() {
        User owner = user("Thandi Mokoena"), u1 = user("Una One"), u2 = user("Dee Two"), u3 = user("Tre Three"), u4 = user("Quo Four");
        String id = stokvel(owner);
        String generic = "This invitation is not valid or has expired";

        assertEquals(generic, messageOf(() -> service.accept("0000-0000-0000-0000", u1)), "unknown code");
        assertEquals(generic, messageOf(() -> service.accept("not a code", u1)), "garbage");
        assertEquals(generic, messageOf(() -> service.preview("", u1)));

        CreatedInviteLink expired = service.createLink(id, owner, 1, 5);
        jdbc.update("update stokvel_invite_links set expires_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(60)), expired.link().linkId());
        assertEquals(generic, messageOf(() -> service.accept(expired.code(), u1)));
        assertEquals(generic, messageOf(() -> service.preview(expired.code(), u1)));
        assertEquals("EXPIRED", service.listLinks(id, owner).stream().filter(l -> l.linkId().equals(expired.link().linkId())).findFirst().orElseThrow().status());

        CreatedInviteLink revoked = service.createLink(id, owner, 7, 5);
        assertEquals("REVOKED", service.revokeLink(id, revoked.link().linkId(), owner).status());
        assertEquals(generic, messageOf(() -> service.accept(revoked.code(), u2)));
        assertEquals("REVOKED", service.revokeLink(id, revoked.link().linkId(), owner).status(), "revoking twice is harmless");

        CreatedInviteLink single = service.createLink(id, owner, 7, 1);
        assertEquals("JOINED", service.accept(single.code(), u3).outcome());
        assertEquals(generic, messageOf(() -> service.accept(single.code(), u4)), "single-use link is exhausted");
        assertEquals("EXHAUSTED", service.listLinks(id, owner).stream().filter(l -> l.linkId().equals(single.link().linkId())).findFirst().orElseThrow().status());
        assertFalse(isMember(id, u1) || isMember(id, u2) || isMember(id, u4));
        assertEquals(2, memberships.countByCircleId(id));
    }

    @Test
    void aMemberCannotRevokeAndALinkFromAnotherStokvelCannotBeRevokedHere() {
        User owner = user("Thandi Mokoena"), member = user("Mia Member"), other = user("Olu Owner");
        String id = stokvel(owner), otherId = stokvel(other);
        addAs(id, member, CircleRole.MEMBER);
        CreatedInviteLink l = service.createLink(id, owner, 7, 2);
        assertEquals("FORBIDDEN", statusOf(() -> service.revokeLink(id, l.link().linkId(), member)));
        assertEquals("NOT_FOUND", statusOf(() -> service.revokeLink(otherId, l.link().linkId(), other)), "cross-stokvel revoke");
        assertEquals("ACTIVE", service.listLinks(id, owner).get(0).status());
    }

    @Test
    void anInviteThatRequiresApprovalCreatesARequestInsteadOfAMembership() {
        User owner = user("Thandi Mokoena"), guest = user("Gus Guest");
        String id = stokvel(owner);
        open(owner, id, "Monthly savings pool for neighbours", null, null, true);   // invites need approval
        CreatedInviteLink l = service.createLink(id, owner, 7, 5);
        assertTrue(l.link().requiresApproval());
        assertTrue(service.preview(l.code(), guest).requiresApproval());
        AcceptInviteResult r = service.accept(l.code(), guest);
        assertEquals("REQUESTED", r.outcome());
        assertEquals("INVITE_LINK", r.request().source());
        assertFalse(isMember(id, guest), "not a member until approved");
        assertEquals("ALREADY_REQUESTED", service.accept(l.code(), guest).outcome());
        assertEquals(1, service.listLinks(id, owner).get(0).uses());
        service.approve(id, r.request().requestId(), owner, null);
        assertTrue(isMember(id, guest));
        assertEquals("ALREADY_MEMBER", service.accept(l.code(), guest).outcome());
    }

    @Test
    void aRejectedInviteRequestCannotBeRedeemedAgainWithTheSameLink() {
        User owner = user("Thandi Mokoena"), guest = user("Gus Guest");
        String id = stokvel(owner);
        open(owner, id, "Monthly savings pool for neighbours", null, null, true);
        CreatedInviteLink l = service.createLink(id, owner, 7, 5);
        AcceptInviteResult r = service.accept(l.code(), guest);
        service.reject(id, r.request().requestId(), owner, null);
        assertEquals("CONFLICT", statusOf(() -> service.accept(l.code(), guest)), "duplicate-use protection per person");
        assertFalse(isMember(id, guest));
    }

    @Test
    void aFullStokvelRefusesDirectInviteJoinsWithoutConsumingTheLink() {
        User owner = user("Thandi Mokoena"), guest = user("Gus Guest");
        String id = stokvel(owner);
        open(owner, id, "Tiny group of two people", null, 2, false);
        CreatedInviteLink l = service.createLink(id, owner, 7, 5);
        addAs(id, user("Fill Er"), CircleRole.MEMBER);                      // now 2 of 2
        assertEquals("CONFLICT", statusOf(() -> service.accept(l.code(), guest)));
        assertEquals(0, service.listLinks(id, owner).get(0).uses());
        assertFalse(isMember(id, guest));
    }

    @Test
    void concurrentRedemptionsOfAMultiUseLinkNeverExceedMaxUses() throws Exception {
        User owner = user("Thandi Mokoena");
        String id = stokvel(owner);
        CreatedInviteLink l = service.createLink(id, owner, 7, 3);
        List<Callable<Object>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) { User g = user("Guest " + i); jobs.add(() -> service.accept(l.code(), g)); }
        List<Object> results = concurrently(jobs);
        assertEquals(3, results.stream().filter(x -> x instanceof AcceptInviteResult r && r.outcome().equals("JOINED")).count(), results.toString());
        assertEquals(4, memberships.countByCircleId(id));
        assertEquals(3, service.listLinks(id, owner).get(0).uses());
    }

    @Test
    void concurrentRedemptionsByTheSamePersonJoinOnce() throws Exception {
        User owner = user("Thandi Mokoena"), guest = user("Gus Guest");
        String id = stokvel(owner);
        CreatedInviteLink l = service.createLink(id, owner, 7, 10);
        List<Callable<Object>> jobs = new ArrayList<>();
        for (int i = 0; i < 6; i++) jobs.add(() -> service.accept(l.code(), guest));
        List<Object> results = concurrently(jobs);
        assertEquals(1, results.stream().filter(x -> x instanceof AcceptInviteResult r && r.outcome().equals("JOINED")).count(), results.toString());
        assertEquals(2, memberships.countByCircleId(id));
        assertEquals(1, service.listLinks(id, owner).get(0).uses());
    }

    @Test
    void newMembersAppearInTheExistingMembershipListAndGetNoAdminRights() {
        User owner = user("Thandi Mokoena"), guest = user("Gus Guest"), applicant = user("Ann Applicant");
        String id = stokvel(owner);
        open(owner, id, "Monthly savings pool for neighbours", null, null, false);
        service.accept(service.createLink(id, owner, 7, 5).code(), guest);
        service.approve(id, service.requestToJoin(id, applicant, null).requestId(), owner, null);
        List<String> roster = circleService.listMembers(id, owner).stream().map(m -> m.userId() + ":" + m.role()).toList();
        assertTrue(roster.contains(guest.getId() + ":MEMBER"), roster.toString());
        assertTrue(roster.contains(applicant.getId() + ":MEMBER"), roster.toString());
        assertEquals("FORBIDDEN", statusOf(() -> service.createLink(id, guest, 1, 1)), "new members cannot invite");
        assertEquals("FORBIDDEN", statusOf(() -> service.approve(id, UUID.randomUUID().toString(), applicant, null)));
    }
}
