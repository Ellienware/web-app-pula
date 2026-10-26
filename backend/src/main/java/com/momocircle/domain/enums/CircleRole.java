package com.momocircle.domain.enums;

public enum CircleRole {
    OWNER,
    ADMIN,
    MEMBER,
    /** Stokvel only: records payouts and creates requests; does NOT inherit ADMIN powers. */
    TREASURER,
    /** Stokvel only: creates/approves requests and can cancel them; does NOT inherit ADMIN powers. */
    CHAIRPERSON
}
