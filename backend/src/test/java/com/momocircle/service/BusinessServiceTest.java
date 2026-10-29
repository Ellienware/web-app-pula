package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.UploadedImage;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.domain.enums.BusinessRegistrationStatus;
import com.momocircle.domain.enums.BusinessType;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.dto.MarketDtos.BusinessResponse;
import com.momocircle.dto.MarketDtos.CreateBusinessRequest;
import com.momocircle.dto.MarketDtos.UpdateBusinessRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.UploadedImageRepository;
import com.momocircle.services.UploadedImageRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessServiceTest {

    private static final String LOGO_ID =
            "3f2b9c1e-1111-4222-8333-444455556666";

    private static final String LOGO_URL =
            UploadedImageRules.PATH_PREFIX + LOGO_ID;

    private BusinessRepository businessRepository;
    private UploadedImageRepository uploadedImageRepository;
    private BusinessService service;
    private User owner;

    @BeforeEach
    void setUp() {
        businessRepository = mock(BusinessRepository.class);
        uploadedImageRepository = mock(UploadedImageRepository.class);
        service = new BusinessService(businessRepository, uploadedImageRepository);

        owner = new User();
        owner.setId("user-1");
        owner.setFullName("Thandi Nkosi");

        when(businessRepository.save(any(Business.class)))
                .thenAnswer(invocation -> {
                    Business business = invocation.getArgument(0);
                    if (business.getId() == null) {
                        business.setId("business-1");
                    }
                    return business;
                });

        when(uploadedImageRepository.save(any(UploadedImage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubPendingLogo() {
        UploadedImage logo = new UploadedImage();
        logo.setId(LOGO_ID);
        logo.setUploadedBy(owner.getId());
        logo.setBusinessId(null);

        when(uploadedImageRepository
                .findByIdAndUploadedByAndBusinessIdIsNull(
                        LOGO_ID, owner.getId()))
                .thenReturn(Optional.of(logo));
    }

    @Test
    void createRejectsMissingBusinessTypeInsteadOfDefaulting() {
        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.empty());

        CreateBusinessRequest req = new CreateBusinessRequest(
                "Thandi's Spaza", null, null, "Grocery", null, "Durban",
                null, null, null, null, null, null, null, null, null
        );

        ApiException ex = assertThrows(
                ApiException.class,
                () -> service.create(owner, req)
        );

        assertEquals(400, ex.getStatus().value());
        verify(businessRepository, never()).save(any(Business.class));
    }

    @Test
    void createDefaultsToCanSellToConsumersWhenCapabilitiesNotSpecified() {
        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.empty());
        stubPendingLogo();

        CreateBusinessRequest req = new CreateBusinessRequest(
                "Thandi's Spaza", null, LOGO_URL, "Grocery",
                BusinessType.LOCAL_BUSINESS, "Durban",
                null, null, null, null, null, null, null, null, null
        );

        BusinessResponse response = service.create(owner, req);

        assertEquals(
                List.of(BusinessCapability.CAN_SELL_TO_CONSUMERS),
                response.capabilities()
        );
        assertEquals(LOGO_URL, response.logoUrl());
    }

    @Test
    void wholesalerDefaultsToConsumerSalesAndBusinessSupply() {
        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.empty());
        stubPendingLogo();

        CreateBusinessRequest req = new CreateBusinessRequest(
                "Thandi Wholesale", null, LOGO_URL, "Wholesale",
                BusinessType.WHOLESALER_SUPPLIER, "Durban",
                null, null, null, null, null, null, null, null, null
        );

        BusinessResponse response = service.create(owner, req);

        assertTrue(response.capabilities()
                .contains(BusinessCapability.CAN_SELL_TO_CONSUMERS));
        assertTrue(response.capabilities()
                .contains(BusinessCapability.CAN_SUPPLY_BUSINESSES));
    }

    @Test
    void createHonoursExplicitCapabilities() {
        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.empty());
        stubPendingLogo();

        CreateBusinessRequest req = new CreateBusinessRequest(
                "Thandi's Spaza", null, LOGO_URL, "Grocery",
                BusinessType.LOCAL_BUSINESS, "Durban",
                null, null, null, null,
                List.of(
                        BusinessCapability.CAN_SELL_TO_CONSUMERS,
                        BusinessCapability.CAN_BUY_STOCK
                ),
                null, null, null, null
        );

        BusinessResponse response = service.create(owner, req);

        assertEquals(2, response.capabilities().size());
        assertTrue(response.capabilities().containsAll(
                List.of(
                        BusinessCapability.CAN_SELL_TO_CONSUMERS,
                        BusinessCapability.CAN_BUY_STOCK
                )
        ));
    }

    @Test
    void wholesalerExplicitCapabilitiesStillIncludeRequiredCapabilities() {
        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.empty());
        stubPendingLogo();

        CreateBusinessRequest req = new CreateBusinessRequest(
                "Thandi Wholesale", null, LOGO_URL, "Wholesale",
                BusinessType.WHOLESALER_SUPPLIER, "Durban",
                null, null, null, null,
                List.of(BusinessCapability.CAN_BUY_STOCK),
                null, null, null, null
        );

        BusinessResponse response = service.create(owner, req);

        assertTrue(response.capabilities()
                .contains(BusinessCapability.CAN_SELL_TO_CONSUMERS));
        assertTrue(response.capabilities()
                .contains(BusinessCapability.CAN_SUPPLY_BUSINESSES));
        assertTrue(response.capabilities()
                .contains(BusinessCapability.CAN_BUY_STOCK));
    }

    @Test
    void createRejectsExplicitlyEmptyCapabilities() {
        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.empty());
        stubPendingLogo();

        CreateBusinessRequest req = new CreateBusinessRequest(
                "Thandi's Spaza", null, LOGO_URL, "Grocery",
                BusinessType.LOCAL_BUSINESS, "Durban",
                null, null, null, null,
                List.of(), null, null, null, null
        );

        assertThrows(ApiException.class, () -> service.create(owner, req));
    }

    @Test
    void createHonoursExplicitTypeAndFulfilmentOptions() {
        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.empty());
        stubPendingLogo();

        CreateBusinessRequest req = new CreateBusinessRequest(
                "Corner Mechanic", null, LOGO_URL, "Automotive",
                BusinessType.SERVICE_PROVIDER, "Durban",
                null, null, null,
                List.of(FulfilmentOption.CUSTOMER_PICKUP),
                null, null, null, null, null
        );

        BusinessResponse response = service.create(owner, req);

        assertEquals(BusinessType.SERVICE_PROVIDER, response.businessType());
        assertEquals(
                List.of(FulfilmentOption.CUSTOMER_PICKUP),
                response.fulfilmentOptions()
        );
    }

    @Test
    void createDefaultsToNotRegisteredWhenRegistrationNotSpecified() {
        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.empty());
        stubPendingLogo();

        CreateBusinessRequest req = new CreateBusinessRequest(
                "Thandi's Spaza", null, LOGO_URL, "Grocery",
                BusinessType.LOCAL_BUSINESS, "Durban",
                null, null, null, null, null, null, null, null, null
        );

        BusinessResponse response = service.create(owner, req);

        assertEquals(
                BusinessRegistrationStatus.NOT_REGISTERED,
                response.registrationStatus()
        );
        assertNull(response.registrationNumber());
    }

    @Test
    void createHonoursExplicitRegistrationDetails() {
        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.empty());
        stubPendingLogo();

        CreateBusinessRequest req = new CreateBusinessRequest(
                "Thandi's Spaza", null, LOGO_URL, "Grocery",
                BusinessType.LOCAL_BUSINESS, "Durban",
                null, null, null, null, null,
                BusinessRegistrationStatus.FORMALLY_REGISTERED,
                "ENT2024/123456/07", null, null
        );

        BusinessResponse response = service.create(owner, req);

        assertEquals(
                BusinessRegistrationStatus.FORMALLY_REGISTERED,
                response.registrationStatus()
        );
        assertEquals("ENT2024/123456/07", response.registrationNumber());
    }

    @Test
    void updateRejectsBusinessTypeChange() {
        Business existing = new Business();
        existing.setOwner(owner);
        existing.setName("Existing Shop");
        existing.setCategory("Grocery");
        existing.setBusinessType(BusinessType.LOCAL_BUSINESS);

        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.of(existing));

        UpdateBusinessRequest req = new UpdateBusinessRequest(
                null, null, null, null, BusinessType.INFORMAL_TRADER,
                null, null, null, null,
                List.of(
                        FulfilmentOption.SELLER_DELIVERY,
                        FulfilmentOption.MOMORIDE_DELIVERY
                ),
                null, null, null, null, null, null
        );

        ApiException ex = assertThrows(
                ApiException.class,
                () -> service.update(owner, req)
        );

        assertEquals(400, ex.getStatus().value());
        assertEquals(BusinessType.LOCAL_BUSINESS, existing.getBusinessType());
        verify(businessRepository, never()).save(any(Business.class));
    }

    @Test
    void updateCanAddSupplyCapabilityToAnExistingSpaza() {
        Business existing = new Business();
        existing.setOwner(owner);
        existing.setName("Existing Shop");
        existing.setCategory("Grocery");
        existing.setBusinessType(BusinessType.INFORMAL_TRADER);

        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.of(existing));

        UpdateBusinessRequest req = new UpdateBusinessRequest(
                null, null, null, null, null,
                null, null, null, null, null,
                List.of(
                        BusinessCapability.CAN_SELL_TO_CONSUMERS,
                        BusinessCapability.CAN_BUY_STOCK
                ),
                null, null, null, null, null
        );

        BusinessResponse response = service.update(owner, req);

        assertEquals(2, response.capabilities().size());
        assertTrue(response.capabilities()
                .contains(BusinessCapability.CAN_BUY_STOCK));
    }

    @Test
    void updateLeavesCapabilitiesAndRegistrationUnchangedWhenNotProvided() {
        Business existing = new Business();
        existing.setOwner(owner);
        existing.setName("Existing Shop");
        existing.setCategory("Grocery");
        existing.setCapabilities(new java.util.LinkedHashSet<>(
                List.of(
                        BusinessCapability.CAN_SUPPLY_BUSINESSES,
                        BusinessCapability.CAN_MANAGE_DELIVERY_FLEET
                )
        ));
        existing.setRegistrationStatus(
                BusinessRegistrationStatus.REGISTRATION_IN_PROGRESS
        );
        existing.setRegistrationNumber("ENT2024/999999/07");

        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.of(existing));

        UpdateBusinessRequest req = new UpdateBusinessRequest(
                "New Name", null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null
        );

        BusinessResponse response = service.update(owner, req);

        assertEquals(2, response.capabilities().size());
        assertTrue(response.capabilities().containsAll(
                List.of(
                        BusinessCapability.CAN_SUPPLY_BUSINESSES,
                        BusinessCapability.CAN_MANAGE_DELIVERY_FLEET
                )
        ));
        assertEquals(
                BusinessRegistrationStatus.REGISTRATION_IN_PROGRESS,
                response.registrationStatus()
        );
        assertEquals("ENT2024/999999/07", response.registrationNumber());
    }

    @Test
    void updateCanProgressRegistrationStatus() {
        Business existing = new Business();
        existing.setOwner(owner);
        existing.setName("Existing Shop");
        existing.setCategory("Grocery");

        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.of(existing));

        UpdateBusinessRequest req = new UpdateBusinessRequest(
                null, null, null, null, null,
                null, null, null, null, null,
                null,
                BusinessRegistrationStatus.REGISTRATION_IN_PROGRESS,
                null, null, null, null
        );

        BusinessResponse response = service.update(owner, req);

        assertEquals(
                BusinessRegistrationStatus.REGISTRATION_IN_PROGRESS,
                response.registrationStatus()
        );
    }

    @Test
    void updateCanClearRegistrationNumberWithBlankString() {
        Business existing = new Business();
        existing.setOwner(owner);
        existing.setName("Existing Shop");
        existing.setCategory("Grocery");
        existing.setRegistrationNumber("ENT2024/111111/07");

        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.of(existing));

        UpdateBusinessRequest req = new UpdateBusinessRequest(
                null, null, null, null, null,
                null, null, null, null, null, null, null, "",
                null, null, null
        );

        BusinessResponse response = service.update(owner, req);

        assertNull(response.registrationNumber());
    }

    @Test
    void createStoresMinimumOrderAmountAndDeliveryFeeWhenProvided() {
        when(businessRepository.findByOwnerId(owner.getId()))
                .thenReturn(Optional.empty());
        stubPendingLogo();

        CreateBusinessRequest req = new CreateBusinessRequest(
                "Thandi's Spaza", null, LOGO_URL, "Grocery",
                BusinessType.LOCAL_BUSINESS, "Durban",
                null, null, null, null, null, null, null,
                new java.math.BigDecimal("100.00"),
                new java.math.BigDecimal("25.00")
        );

        BusinessResponse response = service.create(owner, req);

        assertEquals(
                0,
                new java.math.BigDecimal("100.00")
                        .compareTo(response.minimumOrderAmount())
        );
        assertEquals(
                0,
                new java.math.BigDecimal("25.00")
                        .compareTo(response.deliveryFee())
        );
    }
}
