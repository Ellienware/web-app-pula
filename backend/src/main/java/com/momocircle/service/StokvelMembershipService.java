package com.momocircle.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.momocircle.domain.Circle;
import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.StokvelInviteLink;
import com.momocircle.domain.StokvelInviteLinkUse;
import com.momocircle.domain.StokvelJoinRequest;
import com.momocircle.domain.StokvelProfile;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ActivityType;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.JoinRequestSource;
import com.momocircle.domain.enums.JoinRequestStatus;
import com.momocircle.domain.enums.NotificationType;
import com.momocircle.domain.enums.StokvelType;
import com.momocircle.dto.StokvelMembershipDtos.AcceptInviteResult;
import com.momocircle.dto.StokvelMembershipDtos.CreatedInviteLink;
import com.momocircle.dto.StokvelMembershipDtos.DiscoverableStokvel;
import com.momocircle.dto.StokvelMembershipDtos.InviteLinkView;
import com.momocircle.dto.StokvelMembershipDtos.InvitePreview;
import com.momocircle.dto.StokvelMembershipDtos.JoinRequestView;
import com.momocircle.dto.StokvelMembershipDtos.MembershipSettingsView;
import com.momocircle.dto.StokvelMembershipDtos.PageView;
import com.momocircle.dto.StokvelMembershipDtos.UpdateMembershipSettings;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.CircleMembershipRepository;
import com.momocircle.repository.StokvelInviteLinkRepository;
import com.momocircle.repository.StokvelInviteLinkUseRepository;
import com.momocircle.repository.StokvelJoinRequestRepository;
import com.momocircle.repository.StokvelProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.momocircle.dto.StokvelMembershipDtos.FUNDS_NOTICE;

/**
 * Stokvel discovery, invite links and membership requests, built on the EXISTING membership model: a member is a
 * {@code circle_memberships} row, a stokvel is a circle plus a stokvel profile. There is no parallel membership system and no money
 * here at all.
 *
 * <p>Rules enforced on the server (the UI only mirrors them):
 * <ul>
 *   <li>Stokvels are PRIVATE unless an owner/admin opts in; a non-discoverable stokvel answers "not found" to everyone outside it.</li>
 *   <li>Every path that adds a member first takes the stokvel's profile row lock, so capacity and duplicate checks are serialised.</li>
 *   <li>A person is added only by (a) an admin approving their pending request or (b) redeeming a live invite link that does
 *       not require approval. Rejected, cancelled, expired, revoked or exhausted paths add nothing.</li>
 *   <li>Request states change only through guarded single-statement updates; at most one open request per person per stokvel
 *       is guaranteed by a database constraint; a person can redeem a link at most once.</li>
 *   <li>New members always get the MEMBER role; admin roles are granted only through the existing role-change flow.</li>
 * </ul>
 */
@Service
public class StokvelMembershipService {

    @PersistenceContext
    private EntityManager entityManager;
    private static final String INVALID_INVITE = "This invitation is not valid or has expired";
    private static final int MAX_ACTIVE_LINKS = 20;

    private final StokvelProfileRepository profiles;
    private final CircleMembershipRepository memberships;
    private final StokvelJoinRequestRepository requests;
    private final StokvelInviteLinkRepository links;
    private final StokvelInviteLinkUseRepository linkUses;
    private final StokvelDiscoveryQuery discovery;
    private final CircleService circleService;
    private final StokvelRotationService rotationService;
    private final ActivityService activityService;
    private final NotificationService notificationService;
    private final Duration rejoinCooldown;

    public StokvelMembershipService(StokvelProfileRepository profiles, CircleMembershipRepository memberships,
                                    StokvelJoinRequestRepository requests, StokvelInviteLinkRepository links,
                                    StokvelInviteLinkUseRepository linkUses, StokvelDiscoveryQuery discovery,
                                    CircleService circleService, StokvelRotationService rotationService,
                                    ActivityService activityService, NotificationService notificationService,
                                    @Value("${app.stokvel.rejoin-cooldown-hours:24}") int rejoinCooldownHours) {
        this.profiles = profiles;
        this.memberships = memberships;
        this.requests = requests;
        this.links = links;
        this.linkUses = linkUses;
        this.discovery = discovery;
        this.circleService = circleService;
        this.rotationService = rotationService;
        this.activityService = activityService;
        this.notificationService = notificationService;
        this.rejoinCooldown = Duration.ofHours(rejoinCooldownHours);
    }

    // ================================================================ settings (owner/admin)

    @Transactional(readOnly = true)
    public MembershipSettingsView settings(String circleId, User viewer) {
        adminOf(circleId, viewer);
        StokvelProfile p = profile(circleId);
        return settingsView(p, circleId);
    }

    @Transactional
    public MembershipSettingsView updateSettings(String circleId, User actor, UpdateMembershipSettings req) {
        CircleMembership am = adminOf(circleId, actor);
        StokvelProfile p = lockProfile(circleId);
        String summary = clean(req.publicSummary());
        if (Boolean.TRUE.equals(req.discoverable()) && (summary == null || summary.length() < 10)) {
            throw ApiException.badRequest("Add a short public summary (at least 10 characters) before making the stokvel discoverable");
        }
        long members = memberships.countByCircleId(circleId);
        if (req.maxMembers() != null && req.maxMembers() < members) {
            throw ApiException.badRequest("The member limit cannot be lower than the current number of members (" + members + ")");
        }
        p.setDiscoverable(req.discoverable());
        p.setArea(clean(req.area()));
        p.setPublicSummary(summary);
        p.setMembershipRequirements(clean(req.membershipRequirements()));
        p.setMaxMembers(req.maxMembers());
        p.setInviteRequiresApproval(req.inviteRequiresApproval());
        profiles.save(p);
        activityService.record(am.getCircle(), actor, ActivityType.STOKVEL_DISCOVERY_UPDATED,
                actor.getFullName() + (p.isDiscoverable() ? " made the stokvel discoverable" : " made the stokvel private"));
        return settingsView(p, circleId);
    }

    // ================================================================ discovery

    @Transactional(readOnly = true)
    public PageView<DiscoverableStokvel> discover(User viewer, StokvelDiscoveryQuery.Filter filter, int page, int size) {
        Page<StokvelProfile> result = discovery.search(viewer.getId(), filter,
                PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50))));
        List<DiscoverableStokvel> items = result.getContent().stream().map(p -> card(p, viewer)).toList();
        return new PageView<>(items, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /** One discoverable stokvel's public card. Anything private (or unknown) is "not found", whoever asks. */
    @Transactional(readOnly = true)
    public DiscoverableStokvel publicView(String circleId, User viewer) {
        StokvelProfile p = profiles.findByCircleId(circleId).filter(StokvelProfile::isDiscoverable)
                .orElseThrow(() -> ApiException.notFound("Stokvel not found"));
        if (memberships.findByCircleIdAndUserId(circleId, viewer.getId()).isPresent()) {
            throw ApiException.conflict("You are already a member of this stokvel");
        }
        return card(p, viewer);
    }

    // ================================================================ membership requests

    @Transactional
    public JoinRequestView requestToJoin(String circleId, User user, String message) {
        StokvelProfile p = lockProfile(circleId);
        boolean member = isMember(circleId, user);
        if (!p.isDiscoverable() && !member) throw ApiException.notFound("Stokvel not found"); // private stays private
        if (member) throw ApiException.conflict("You are already a member of this stokvel");
        if (requests.findByCircleIdAndUserIdAndStatus(circleId, user.getId(), JoinRequestStatus.PENDING).isPresent()) {
            throw ApiException.conflict("You already have a pending request for this stokvel");
        }
        requests.findDecided(circleId, user.getId(), JoinRequestStatus.REJECTED).stream().findFirst().ifPresent(prev -> {
            if (prev.getDecidedAt() != null && prev.getDecidedAt().plus(rejoinCooldown).isAfter(Instant.now())) {
                throw ApiException.tooManyRequests("Your last request was declined. You can ask again after " + rejoinCooldown.toHours() + " hours");
            }
        });
        requireCapacity(p, circleId);
        StokvelJoinRequest r = saveRequest(p.getCircle(), user, JoinRequestSource.DISCOVERY, clean(message), null);
        notifyAdmins(p.getCircle(), user, r);
        return view(r, user.getId());
    }

    @Transactional(readOnly = true)
    public List<JoinRequestView> listRequests(String circleId, User admin, JoinRequestStatus status) {
        adminOf(circleId, admin);
        List<StokvelJoinRequest> rows = status == null ? requests.findAllForCircle(circleId) : requests.findForCircleByStatus(circleId, status);
        return rows.stream().map(r -> view(r, admin.getId())).toList();
    }

    @Transactional(readOnly = true)
    public List<JoinRequestView> myRequests(User user) {
        return requests.findForUser(user.getId()).stream().map(r -> view(r, user.getId())).toList();
    }

    @Transactional
    public JoinRequestView approve(String circleId, String requestId, User admin, String note) {
        CircleMembership am = adminOf(circleId, admin);
        StokvelProfile p = lockProfile(circleId);
        StokvelJoinRequest r = requestIn(circleId, requestId);
        requirePending(r);
        User applicant = r.getUser();
        boolean already = isMember(circleId, applicant);
        if (!already) requireCapacity(p, circleId);
        if (requests.decide(r.getId(), circleId, JoinRequestStatus.APPROVED, admin, Instant.now(), clean(note), JoinRequestStatus.PENDING) != 1) {
            throw ApiException.conflict("This request was already decided");
        }

        entityManager.refresh(r);

        if (!already) addMember(p, am.getCircle(), applicant, ActivityType.MEMBER_JOINED, applicant.getFullName() + " joined the stokvel");
        activityService.record(am.getCircle(), admin, ActivityType.STOKVEL_JOIN_APPROVED, admin.getFullName() + " approved " + applicant.getFullName() + "'s request to join");
        notificationService.notify(applicant, NotificationType.STOKVEL_APPROVAL_COMPLETED,
                "Your request to join " + am.getCircle().getName() + " was approved", circleId);
        return view(requests.findById(r.getId()).orElseThrow(), admin.getId());
    }

    @Transactional
    public JoinRequestView reject(String circleId, String requestId, User admin, String note) {
        CircleMembership am = adminOf(circleId, admin);
        StokvelJoinRequest r = requestIn(circleId, requestId);
        requirePending(r);
        if (requests.decide(r.getId(), circleId, JoinRequestStatus.REJECTED, admin, Instant.now(), clean(note), JoinRequestStatus.PENDING) != 1) {
            throw ApiException.conflict("This request was already decided");
        }

        entityManager.refresh(r);

        activityService.record(am.getCircle(), admin, ActivityType.STOKVEL_JOIN_REJECTED, admin.getFullName() + " declined a request to join");
        notificationService.notify(r.getUser(), NotificationType.STOKVEL_APPROVAL_COMPLETED,
                "Your request to join " + am.getCircle().getName() + " was not approved", circleId);
        return view(requests.findById(r.getId()).orElseThrow(), admin.getId());
    }

    @Transactional
    public JoinRequestView cancel(String circleId, String requestId, User user) {
        StokvelJoinRequest r = requests.findById(requestId)
                .filter(x -> x.getCircle().getId().equals(circleId) && x.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> ApiException.notFound("Request not found")); // someone else's request is "not found"
        requirePending(r);
        if (requests.cancelOwn(r.getId(), user.getId(), Instant.now(), JoinRequestStatus.PENDING, JoinRequestStatus.CANCELLED) != 1) {
            throw ApiException.conflict("This request was already decided");
        }

        entityManager.refresh(r);

        activityService.record(r.getCircle(), user, ActivityType.STOKVEL_JOIN_CANCELLED, user.getFullName() + " withdrew a request to join");
        return view(requests.findById(r.getId()).orElseThrow(), user.getId());
    }

    // ================================================================ invite links

    @Transactional
    public CreatedInviteLink createLink(String circleId, User admin, Integer expiresInDays, Integer maxUses) {
        CircleMembership am = adminOf(circleId, admin);
        StokvelProfile p = profile(circleId);
        Instant now = Instant.now();
        long active = links.findForCircle(circleId).stream().filter(l -> statusOf(l, now).equals("ACTIVE")).count();
        if (active >= MAX_ACTIVE_LINKS) {
            throw ApiException.conflict("This stokvel already has " + MAX_ACTIVE_LINKS + " active invite links. Revoke one first");
        }
        int days = expiresInDays == null ? 7 : Math.max(1, Math.min(expiresInDays, 30));
        int uses = maxUses == null ? 1 : Math.max(1, Math.min(maxUses, 100));
        String code = StokvelInviteCodes.generate();
        StokvelInviteLink link = links.save(StokvelInviteLink.create(am.getCircle(), admin, StokvelInviteCodes.hash(code),
                StokvelInviteCodes.hint(code), p.isInviteRequiresApproval(), uses, now.plus(Duration.ofDays(days))));
        activityService.record(am.getCircle(), admin, ActivityType.STOKVEL_INVITE_CREATED, admin.getFullName() + " created an invite link");
        return new CreatedInviteLink(linkView(link, now), StokvelInviteCodes.display(code),
                "Copy this code now. It is shown only once and cannot be recovered. " + FUNDS_NOTICE);
    }

    @Transactional(readOnly = true)
    public List<InviteLinkView> listLinks(String circleId, User admin) {
        adminOf(circleId, admin);
        Instant now = Instant.now();
        return links.findForCircle(circleId).stream().map(l -> linkView(l, now)).toList();
    }

    @Transactional
    public InviteLinkView revokeLink(String circleId, String linkId, User admin) {
        CircleMembership am = adminOf(circleId, admin);
        StokvelInviteLink l = links.findById(linkId)
                .filter(x -> x.getCircle().getId().equals(circleId))
                .orElseThrow(() -> ApiException.notFound("Invite link not found"));

        if (links.revoke(l.getId(), circleId, admin, Instant.now()) == 1) {
            entityManager.refresh(l);
            activityService.record(am.getCircle(), admin, ActivityType.STOKVEL_INVITE_REVOKED,
                    admin.getFullName() + " revoked an invite link");
        }

        return linkView(links.findById(l.getId()).orElseThrow(), Instant.now());
    }

    /** What a code leads to, shown BEFORE anyone accepts. Public-card information only; no money, no member details. */
    @Transactional(readOnly = true)
    public InvitePreview preview(String code, User user) {
        StokvelInviteLink l = liveLink(code);
        StokvelProfile p = profile(l.getCircle().getId());
        long members = memberships.countByCircleId(l.getCircle().getId());
        return new InvitePreview(l.getCircle().getName(), organiser(p), p.getStokvelType().name(), p.getContributionFrequency().name(),
                p.getContributionAmount(), l.getCircle().getCurrency(), publicText(p), p.getMembershipRequirements(), (int) members,
                p.getMaxMembers(), l.isRequiresApproval(), l.getExpiresAt(), isMember(l.getCircle().getId(), user), FUNDS_NOTICE);
    }

    @Transactional
    public AcceptInviteResult accept(String code, User user) {
        String circleId = invitedCircleId(code);
        StokvelProfile p = lockProfile(circleId);                 // serialise with every other join path for this stokvel
        StokvelInviteLink link = liveOrReplayableLink(code, user);
        Circle circle = p.getCircle();
        String circleKey = circle.getId();

        // Idempotent replays and "already in" cases never consume a use.
        if (isMember(circleKey, user)) return new AcceptInviteResult("ALREADY_MEMBER", circleKey, circle.getName(), null);
        Optional<StokvelJoinRequest> pending = requests.findByCircleIdAndUserIdAndStatus(circleKey, user.getId(), JoinRequestStatus.PENDING);
        if (pending.isPresent()) return new AcceptInviteResult("ALREADY_REQUESTED", null, circle.getName(), view(pending.get(), user.getId()));
        if (linkUses.findByLinkIdAndUserId(link.getId(), user.getId()).isPresent()) {
            throw ApiException.conflict("You have already used this invitation");
        }
        if (!isLive(link, Instant.now())) throw ApiException.notFound(INVALID_INVITE);

        if (!link.isRequiresApproval()) requireCapacity(p, circleKey);
        if (links.consume(link.getId(), Instant.now()) != 1) throw ApiException.notFound(INVALID_INVITE); // exhausted/expired/revoked in the meantime
        try {
            linkUses.saveAndFlush(StokvelInviteLinkUse.of(link.getId(), user.getId(), link.isRequiresApproval() ? "REQUESTED" : "JOINED"));
        } catch (DataIntegrityViolationException dup) {
            throw ApiException.conflict("You have already used this invitation");
        }
        if (link.isRequiresApproval()) {
            StokvelJoinRequest r = saveRequest(circle, user, JoinRequestSource.INVITE_LINK, null, link.getId());
            notifyAdmins(circle, user, r);
            return new AcceptInviteResult("REQUESTED", null, circle.getName(), view(r, user.getId()));
        }
        addMember(p, circle, user, ActivityType.STOKVEL_JOINED_VIA_INVITE, user.getFullName() + " joined with an invite link");
        return new AcceptInviteResult("JOINED", circleKey, circle.getName(), null);
    }

    // ================================================================ helpers

    private CircleMembership adminOf(String circleId, User user) {
        CircleMembership m = circleService.requireMembership(circleId, user);
        circleService.requireAdmin(m);
        return m;
    }

    private StokvelProfile profile(String circleId) {
        return profiles.findByCircleId(circleId).orElseThrow(() -> ApiException.notFound("Stokvel not found"));
    }

    private StokvelProfile lockProfile(String circleId) {
        return profiles.findByCircleIdForUpdate(circleId).orElseThrow(() -> ApiException.notFound("Stokvel not found"));
    }

    private boolean isMember(String circleId, User user) {
        return memberships.findByCircleIdAndUserId(circleId, user.getId()).isPresent();
    }

    private void requireCapacity(StokvelProfile p, String circleId) {
        if (p.getMaxMembers() != null && memberships.countByCircleId(circleId) >= p.getMaxMembers()) {
            throw ApiException.conflict("This stokvel is full");
        }
    }

    private StokvelJoinRequest requestIn(String circleId, String requestId) {
        return requests.findById(requestId).filter(r -> r.getCircle().getId().equals(circleId))
                .orElseThrow(() -> ApiException.notFound("Request not found"));
    }

    private static void requirePending(StokvelJoinRequest r) {
        if (r.getStatus() != JoinRequestStatus.PENDING) {
            throw ApiException.conflict("This request was already " + r.getStatus().name().toLowerCase(Locale.ROOT));
        }
    }

    private StokvelJoinRequest saveRequest(Circle circle, User user, JoinRequestSource source, String message, String linkId) {
        try {
            StokvelJoinRequest r = requests.saveAndFlush(StokvelJoinRequest.open(circle, user, source, message, linkId));
            activityService.record(circle, user, ActivityType.STOKVEL_JOIN_REQUESTED, user.getFullName() + " asked to join");
            return r;
        } catch (DataIntegrityViolationException dup) {
            throw ApiException.conflict("You already have a pending request for this stokvel");
        }
    }

    /** The only place a member is added by this feature. New members are always plain MEMBERs. */
    private void addMember(StokvelProfile p, Circle circle, User user, ActivityType activity, String message) {
        CircleMembership m = new CircleMembership();
        m.setCircle(circle);
        m.setUser(user);
        m.setRole(CircleRole.MEMBER);
        try {
            memberships.saveAndFlush(m);
        } catch (DataIntegrityViolationException dup) {
            throw ApiException.conflict("Already a member of this stokvel");
        }
        if (p.getStokvelType() == StokvelType.ROTATIONAL) rotationService.syncSlots(p);
        activityService.record(circle, user, activity, message);
    }

    private void notifyAdmins(Circle circle, User applicant, StokvelJoinRequest r) {
        for (CircleMembership m : memberships.findByCircleId(circle.getId())) {
            if (m.getRole() == CircleRole.OWNER || m.getRole() == CircleRole.ADMIN) {
                notificationService.notify(m.getUser(), NotificationType.STOKVEL_APPROVAL_REQUESTED,
                        applicant.getFullName() + " asked to join " + circle.getName(), circle.getId());
            }
        }
    }

    // ---- invite resolution (every failure is the same generic message, so codes cannot be probed) ----

    private String invitedCircleId(String code) {
        return resolve(code).getCircle().getId();
    }

    private StokvelInviteLink resolve(String code) {
        String norm = StokvelInviteCodes.normalise(code).orElseThrow(() -> ApiException.notFound(INVALID_INVITE));
        return links.findByCodeHash(StokvelInviteCodes.hash(norm)).orElseThrow(() -> ApiException.notFound(INVALID_INVITE));
    }

    private StokvelInviteLink liveLink(String code) {
        StokvelInviteLink l = resolve(code);
        if (!isLive(l, Instant.now())) throw ApiException.notFound(INVALID_INVITE);
        return l;
    }

    /** Live links, plus a dead link when THIS user already used it (so a retry gets an idempotent answer, not a confusing 404). */
    private StokvelInviteLink liveOrReplayableLink(String code, User user) {
        StokvelInviteLink l = resolve(code);
        if (isLive(l, Instant.now()) || linkUses.findByLinkIdAndUserId(l.getId(), user.getId()).isPresent()
                || isMember(l.getCircle().getId(), user)) {
            return l;
        }
        throw ApiException.notFound(INVALID_INVITE);
    }

    private static boolean isLive(StokvelInviteLink l, Instant now) {
        return l.getRevokedAt() == null && l.getExpiresAt().isAfter(now) && l.getUsesCount() < l.getMaxUses();
    }

    private static String statusOf(StokvelInviteLink l, Instant now) {
        if (l.getRevokedAt() != null) return "REVOKED";
        if (!l.getExpiresAt().isAfter(now)) return "EXPIRED";
        if (l.getUsesCount() >= l.getMaxUses()) return "EXHAUSTED";
        return "ACTIVE";
    }

    // ---- views ----

    private MembershipSettingsView settingsView(StokvelProfile p, String circleId) {
        int pending = requests.findForCircleByStatus(circleId, JoinRequestStatus.PENDING).size();
        return new MembershipSettingsView(p.isDiscoverable(), p.getArea(), p.getPublicSummary(), p.getMembershipRequirements(),
                p.getMaxMembers(), p.isInviteRequiresApproval(), (int) memberships.countByCircleId(p.getCircle().getId()), pending);
    }

    private DiscoverableStokvel card(StokvelProfile p, User viewer) {
        String circleId = p.getCircle().getId();
        int members = (int) memberships.countByCircleId(circleId);
        Integer left = p.getMaxMembers() == null ? null : Math.max(0, p.getMaxMembers() - members);
        Optional<StokvelJoinRequest> pending = requests.findByCircleIdAndUserIdAndStatus(circleId, viewer.getId(), JoinRequestStatus.PENDING);
        return new DiscoverableStokvel(circleId, p.getCircle().getName(), organiser(p), p.getStokvelType().name(),
                p.getContributionFrequency().name(), p.getContributionAmount(), p.getCircle().getCurrency(), publicText(p), p.getArea(),
                p.getMembershipRequirements(), members, p.getMaxMembers(), left, left != null && left == 0,
                pending.isPresent() ? "PENDING" : "NONE", pending.map(StokvelJoinRequest::getId).orElse(null), FUNDS_NOTICE);
    }

    /** Public card text: the owner-written summary, never the private description. */
    private static String publicText(StokvelProfile p) {
        return p.getPublicSummary();
    }

    private String organiser(StokvelProfile p) {
        User owner = p.getCircle().getOwner();
        return owner == null ? "A PULA member" : maskName(owner.getFullName());
    }

    private InviteLinkView linkView(StokvelInviteLink l, Instant now) {
        return new InviteLinkView(l.getId(), l.getCodeHint(), statusOf(l, now), l.getUsesCount(), l.getMaxUses(), l.getExpiresAt(),
                l.getCreatedAt(), l.isRequiresApproval(), l.getCreatedBy().getFullName(), l.getRevokedAt());
    }

    private JoinRequestView view(StokvelJoinRequest r, String viewerId) {
        return new JoinRequestView(r.getId(), r.getCircle().getId(), r.getCircle().getName(), r.getUser().getFullName(), r.getStatus().name(),
                r.getSource().name(), r.getMessage(), r.getCreatedAt(), r.getDecidedAt(),
                r.getDecidedBy() == null ? null : r.getDecidedBy().getFullName(), r.getDecisionNote(),
                r.getStatus() == JoinRequestStatus.PENDING && r.getUser().getId().equals(viewerId));
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    static String maskName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "A PULA member";
        String[] parts = fullName.trim().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + parts[parts.length - 1].substring(0, 1).toUpperCase(Locale.ROOT) + ".";
    }
}
