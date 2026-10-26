package com.momocircle.domain.wallet;

import java.util.EnumSet;
import java.util.Set;

/**
 * Roles for acting on a BUSINESS wallet. OWNER is not stored: it is derived from {@code businesses.owner_id}, so it cannot be
 * granted, changed or removed through the membership table. FINANCE and STAFF are delegated roles stored in
 * {@code business_wallet_members}.
 */
public enum BusinessWalletRole {
    OWNER(EnumSet.allOf(Permission.class)),
    FINANCE(EnumSet.of(Permission.VIEW, Permission.REQUEST, Permission.SEND, Permission.WITHDRAW, Permission.FUND)),
    STAFF(EnumSet.of(Permission.VIEW, Permission.REQUEST));

    public enum Permission { VIEW, REQUEST, SEND, WITHDRAW, FUND, MANAGE_MEMBERS }

    private final Set<Permission> permissions;

    BusinessWalletRole(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public boolean allows(Permission p) { return permissions.contains(p); }

    public Set<Permission> permissions() { return permissions; }

    /** Roles that may be granted through the membership table (never OWNER). */
    public boolean isDelegable() { return this != OWNER; }
}
