package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.CircleMembership;
import com.momocircle.domain.enums.CircleRole;
import com.momocircle.domain.enums.CircleType;
import com.momocircle.dto.CircleDtos.CreateCircleRequest;
import com.momocircle.exception.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Adding TREASURER/CHAIRPERSON must not silently grant them the legacy ADMIN powers; ADMIN must keep them. */
class StokvelAdminBoundaryTest {

    private final CircleService circles = new CircleService(null, null, null, null, null, null);

    private static CircleMembership as(CircleRole role) {
        CircleMembership m = new CircleMembership();
        m.setRole(role);
        return m;
    }

    @Test
    void ownerAndAdminStillPassTheLegacyAdminCheck() {
        assertDoesNotThrow(() -> circles.requireAdmin(as(CircleRole.OWNER)));
        assertDoesNotThrow(() -> circles.requireAdmin(as(CircleRole.ADMIN)));
    }

    @Test
    void memberTreasurerAndChairpersonFailTheLegacyAdminCheck() {
        for (CircleRole r : new CircleRole[] {CircleRole.MEMBER, CircleRole.TREASURER, CircleRole.CHAIRPERSON}) {
            ApiException e = assertThrows(ApiException.class, () -> circles.requireAdmin(as(r)));
            assertEquals("FORBIDDEN", e.getStatus().name(), r + " must not be an admin of the generic Savings features");
        }
    }

    @Test
    void stokvelCirclesCannotBeCreatedThroughTheGenericEndpoint() {
        ApiException e = assertThrows(ApiException.class, () -> circles.create(null,
                new CreateCircleRequest("X", null, CircleType.STOKVEL, "ZAR", new BigDecimal("10.00"))));
        assertEquals("BAD_REQUEST", e.getStatus().name());
    }
}
