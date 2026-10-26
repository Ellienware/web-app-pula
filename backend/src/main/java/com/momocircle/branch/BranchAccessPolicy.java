package com.momocircle.branch;

/**
 * Pure authorisation rules for branch management. The service computes the caller's {@link Relation} to the branch from the
 * database on every request (so removing an assignment takes effect immediately) and asks this policy what is allowed.
 *
 * <p>OWNER: the authenticated owner of the parent business - everything, for every branch of THAT business only.
 * MANAGER: assigned to this branch only - read, update operating details, manage STAFF here; never status, never other managers.
 * STAFF: assigned to this branch only - read-only. NONE: no access (reported as "not found" by callers).
 */
public final class BranchAccessPolicy {

    public enum Relation { OWNER, MANAGER, STAFF, NONE }

    public enum Action { VIEW, UPDATE_DETAILS, CHANGE_STATUS, VIEW_ASSIGNMENTS, MANAGE_STAFF, MANAGE_MANAGERS }

    private BranchAccessPolicy() { }

    public static boolean allows(Relation r, Action a) {
        if (r == null || a == null) return false;
        return switch (r) {
            case OWNER -> true;
            case MANAGER -> a == Action.VIEW || a == Action.UPDATE_DETAILS || a == Action.VIEW_ASSIGNMENTS || a == Action.MANAGE_STAFF;
            case STAFF -> a == Action.VIEW;
            case NONE -> false;
        };
    }

    /** May {@code actor} grant/remove the given role? A manager cannot create or remove managers, so cannot escalate anyone. */
    public static boolean mayAssign(Relation actor, BranchRole target) {
        if (target == null) return false;
        return target == BranchRole.MANAGER ? allows(actor, Action.MANAGE_MANAGERS) : allows(actor, Action.MANAGE_STAFF);
    }

    /** Self-service changes to one's own assignment are never allowed (no self-promotion, no self-assignment). */
    public static boolean mayChangeOwnAssignment(Relation actor) {
        return false;
    }

    // ---------------------------------------------------------------- per-staff permissions

    /** The permissions a caller effectively holds at a branch: all for the owner, exactly the stored set for an assignee, none otherwise. */
    public static java.util.Set<BranchPermission> effective(Relation r, java.util.Set<BranchPermission> stored) {
        if (r == Relation.OWNER) return java.util.EnumSet.allOf(BranchPermission.class);
        if ((r == Relation.MANAGER || r == Relation.STAFF) && stored != null && !stored.isEmpty()) return java.util.EnumSet.copyOf(stored);
        return java.util.EnumSet.noneOf(BranchPermission.class);
    }

    public static boolean has(Relation r, java.util.Set<BranchPermission> stored, BranchPermission p) {
        return p != null && effective(r, stored).contains(p);
    }

    /**
     * May {@code actor} set {@code requested} as the permission set of an assignment with role {@code target}?
     * Owner: anything. Manager: only for STAFF, and only permissions the manager holds themselves (no escalation beyond their own
     * authority). Staff and outsiders: never. Nobody can change their own permissions (the service rejects that before this is asked).
     */
    public static boolean mayGrant(Relation actor, java.util.Set<BranchPermission> actorStored, BranchRole target,
                                   java.util.Set<BranchPermission> requested) {
        if (target == null || requested == null) return false;
        if (actor == Relation.OWNER) return true;
        if (actor == Relation.MANAGER && target == BranchRole.STAFF) {
            return effective(Relation.MANAGER, actorStored).containsAll(requested);
        }
        return false;
    }
}
