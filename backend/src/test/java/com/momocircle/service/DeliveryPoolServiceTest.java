package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.DeliveryPoolMember;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.domain.momoride.Driver;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.DeliveryPoolMemberRepository;
import com.momocircle.repository.UserRepository;
import com.momocircle.repository.momoride.DriverRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** NOT VERIFIED IN THIS SANDBOX - no Maven network access. Run ./mvnw test locally. */
class DeliveryPoolServiceTest {

    private DeliveryPoolMemberRepository members;
    private DeliveryPoolService service;
    private User owner, driverUser;
    private Business business;
    private Driver driver;
    private com.momocircle.repository.momoride.TaxiRepository taxis;

    @BeforeEach
    void setUp() {
        members = mock(DeliveryPoolMemberRepository.class);
        UserRepository users = mock(UserRepository.class);
        DriverRepository drivers = mock(DriverRepository.class);
        BusinessService bs = mock(BusinessService.class);
        taxis = mock(com.momocircle.repository.momoride.TaxiRepository.class);
        service = new DeliveryPoolService(bs, members, users, drivers, mock(NotificationService.class), taxis);

        owner = new User();
        driverUser = new User();
        driverUser.setPhoneNumber("0821234567");
        business = new Business();
        business.setOwner(owner);
        business.getCapabilities().add(BusinessCapability.CAN_MANAGE_DELIVERY_FLEET);
        driver = new Driver();
        driver.setUser(driverUser);
        when(bs.requireOwn(owner)).thenReturn(business);
        when(users.findByPhoneNumber("0821234567")).thenReturn(Optional.of(driverUser));
        when(drivers.findByUserId(driverUser.getId())).thenReturn(Optional.of(driver));
        when(members.findByBusinessIdAndDriverId(any(), any())).thenReturn(Optional.empty());
        when(members.save(any(DeliveryPoolMember.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void addingADriverCreatesAPendingInviteNotAnActiveMember() {
        var r = service.add(owner, "0821234567");
        assertEquals("PENDING", r.status());
        assertFalse(r.active());
    }

    @Test
    void businessWithoutFleetCapabilityCannotManageAPool() {
        business.getCapabilities().remove(BusinessCapability.CAN_MANAGE_DELIVERY_FLEET);
        assertThrows(ApiException.class, () -> service.add(owner, "0821234567"));
    }

    @Test
    void unknownPhoneGivesTheSameGenericErrorAsANonDriver() {
        ApiException ex = assertThrows(ApiException.class, () -> service.add(owner, "0000000000"));
        assertTrue(ex.getMessage().contains("No PULA driver"));
    }

    @Test
    void onlyTheInvitedDriverCanAcceptAndOnlyWhilePending() {
        DeliveryPoolMember m = new DeliveryPoolMember();
        m.setBusiness(business);
        m.setDriver(driver);
        m.setStatus("PENDING");
        DriverRepository drivers = mock(DriverRepository.class);
        when(drivers.findByUserId(driverUser.getId())).thenReturn(Optional.of(driver));
        DeliveryPoolService svc = new DeliveryPoolService(mock(BusinessService.class), members,
                mock(UserRepository.class), drivers, mock(NotificationService.class), taxis);
        when(members.findByIdAndDriverId(m.getId(), driver.getId())).thenReturn(Optional.of(m));

        svc.respondToInvite(driverUser, m.getId(), true);
        assertTrue(m.isActive());
        assertEquals("ACTIVE", m.getStatus());
        assertThrows(ApiException.class, () -> svc.respondToInvite(driverUser, m.getId(), true));
    }

    @Test
    void driverWithARegisteredTaxiAlsoHoldsTaxiDriverCapability() {
        assertFalse(service.capabilitiesFor(driverUser)
                .contains(DeliveryPoolService.DriverCapability.TAXI_DRIVER));
        when(taxis.findByDriverIdOrderByCreatedAtDesc(driver.getId()))
                .thenReturn(java.util.List.of(new com.momocircle.domain.momoride.Taxi()));
        var caps = service.capabilitiesFor(driverUser);
        assertTrue(caps.contains(DeliveryPoolService.DriverCapability.INDEPENDENT_DRIVER));
        assertTrue(caps.contains(DeliveryPoolService.DriverCapability.TAXI_DRIVER));
    }
}
