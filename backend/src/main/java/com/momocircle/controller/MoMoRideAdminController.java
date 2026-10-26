package com.momocircle.controller;

import com.momocircle.domain.momoride.Driver;
import com.momocircle.domain.momoride.MoMoRideReport;
import com.momocircle.domain.momoride.Taxi;
import com.momocircle.dto.momoride.MoMoRideDtos.*;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.momoride.MoMoRideService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

/**
 * Every method resolves an ADMIN-role caller via CurrentUserService.requireAdmin()
 * first, mirroring AdminController — a non-admin (or unauthenticated) caller
 * never reaches MoMoRideService's admin methods.
 */
@RestController
@RequestMapping("/api/admin/momoride")
public class MoMoRideAdminController {

    private final MoMoRideService service;
    private final CurrentUserService currentUser;

    public MoMoRideAdminController(MoMoRideService service, CurrentUserService currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/drivers")
    public Page<DriverResponse> drivers(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        currentUser.requireAdmin();
        return service.adminListDrivers(PageRequest.of(page, Math.min(size, 100)));
    }

    @PostMapping("/drivers/{id}/suspend")
    public void suspendDriver(@PathVariable String id) {
        service.adminSetDriverStatus(currentUser.requireAdmin(), id, Driver.DriverStatus.SUSPENDED);
    }

    @PostMapping("/drivers/{id}/activate")
    public void activateDriver(@PathVariable String id) {
        service.adminSetDriverStatus(currentUser.requireAdmin(), id, Driver.DriverStatus.ACTIVE);
    }

    @GetMapping("/taxis")
    public Page<TaxiResponse> taxis(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        currentUser.requireAdmin();
        return service.adminListTaxis(PageRequest.of(page, Math.min(size, 100)));
    }

    @PostMapping("/taxis/{id}/suspend")
    public void suspendTaxi(@PathVariable String id) {
        service.adminSetTaxiStatus(currentUser.requireAdmin(), id, Taxi.TaxiStatus.SUSPENDED);
    }

    @PostMapping("/taxis/{id}/activate")
    public void activateTaxi(@PathVariable String id) {
        service.adminSetTaxiStatus(currentUser.requireAdmin(), id, Taxi.TaxiStatus.ACTIVE);
    }

    @GetMapping("/rides")
    public Page<RideResponse> rides(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        currentUser.requireAdmin();
        return service.adminListRides(PageRequest.of(page, Math.min(size, 100)));
    }

    @GetMapping("/bookings")
    public Page<RideBookingResponse> bookings(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        currentUser.requireAdmin();
        return service.adminListBookings(PageRequest.of(page, Math.min(size, 100)));
    }

    @GetMapping("/payments")
    public Page<TaxiPaymentRequestResponse> payments(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        currentUser.requireAdmin();
        return service.adminListPaymentRequests(PageRequest.of(page, Math.min(size, 100)));
    }

    @GetMapping("/reports")
    public Page<AdminReportResponse> reports(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        currentUser.requireAdmin();
        return service.adminListReports(PageRequest.of(page, Math.min(size, 100)));
    }

    @PostMapping("/reports/{id}/dismiss")
    public void dismissReport(@PathVariable String id) {
        service.adminResolveReport(currentUser.requireAdmin(), id, MoMoRideReport.Status.DISMISSED);
    }

    @PostMapping("/reports/{id}/review")
    public void reviewReport(@PathVariable String id) {
        service.adminResolveReport(currentUser.requireAdmin(), id, MoMoRideReport.Status.REVIEWED);
    }
}
