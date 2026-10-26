package com.momocircle.controller;

import com.momocircle.exception.ApiException;
import com.momocircle.ussd.UssdRequest;
import com.momocircle.ussd.UssdResponse;
import com.momocircle.ussd.UssdSessionService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Callback endpoint a USSD gateway (Africa's Talking or similar) would call.
 * Deliberately NOT behind the JWT filter — a telecom gateway cannot present
 * a JWT — so it must never trust anything except the phoneNumber the gateway
 * itself reports; every actual authorization decision (which User this
 * phoneNumber maps to, whether they're an active driver, PIN correctness)
 * happens inside UssdSessionService against the real domain, not here.
 * See SecurityConfig — this path is intentionally open, mirroring how
 * /api/auth/** is open, because there is no better credential (like a JWT)
 * available at this layer for a basic-phone channel.
 *
 * <p>When {@code app.momoride.ussd-shared-secret} is set (recommended for any
 * non-dev deployment — most gateways support a configurable outbound header),
 * requests must present it via {@code X-Ussd-Gateway-Key} or are rejected.
 * Left unset in dev so the sandbox flow works without extra setup.
 */
@RestController
@RequestMapping("/api/momoride/ussd")
public class MoMoRideUssdController {

    private final UssdSessionService ussdSessionService;
    private final String sharedSecret;

    public MoMoRideUssdController(UssdSessionService ussdSessionService,
                                   @Value("${app.momoride.ussd-shared-secret:}") String sharedSecret) {
        this.ussdSessionService = ussdSessionService;
        this.sharedSecret = sharedSecret;
    }

    /** Returns the gateway's expected "CON "/"END " prefixed plain-text body. */
    @PostMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public String handle(@Valid @RequestBody UssdRequest req,
                          @RequestHeader(value = "X-Ussd-Gateway-Key", required = false) String presentedKey) {
        if (sharedSecret != null && !sharedSecret.isBlank() && !sharedSecret.equals(presentedKey)) {
            throw ApiException.unauthorized("Invalid gateway key");
        }
        UssdResponse response = ussdSessionService.handle(req);
        return response.raw();
    }
}
