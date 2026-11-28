package com.odel.payment.controller;

import com.odel.common.ApiException;
import com.odel.payment.client.FakeGateway;
import com.odel.payment.client.GatewayException;
import com.odel.payment.client.PaymentGateway;
import com.odel.payment.dto.PaymentDtos.*;
import com.odel.payment.service.Money;
import com.odel.payment.service.RecheckJob;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Development only (profile dev) and only with the fake gateway: the stand-in for Paystack's checkout page, outcomes on demand
 * (each posts a signed webhook as Paystack would), and the 30-minute re-check run as if the clock were later.
 */
@RestController
@Profile("dev")
@RequestMapping("/api/v1/dev/payments")
public class DevPaymentController {
    private final PaymentGateway gateway;
    private final RecheckJob recheck;
    private final Clock clock;

    public DevPaymentController(PaymentGateway gateway, RecheckJob recheck, Clock clock) {
        this.gateway = gateway;
        this.recheck = recheck;
        this.clock = clock;
    }

    private FakeGateway fake() {
        if (gateway instanceof FakeGateway f) return f;
        throw new ApiException(HttpStatus.NOT_FOUND, "not_fake", "The fake gateway is off (ODEL_PAYSTACK_MODE=paystack).");
    }

    @GetMapping(value = "/checkout/{reference}", produces = MediaType.TEXT_HTML_VALUE)
    public String checkout(@PathVariable String reference) {
        FakeGateway f = fake();
        if (!f.known(reference)) throw ApiException.notFound();
        String r = HtmlUtils.htmlEscape(reference);
        String base = "/api/v1/dev/payments/checkout/" + r + "/";
        return """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Fake Paystack checkout</title></head>
                <body style="font-family:Arial,sans-serif;max-width:420px;margin:40px auto;padding:0 16px;color:#1d1d24">
                <p style="color:#888;font-size:13px">Development only: this stands in for Paystack's checkout. Nothing is charged.</p>
                <h2>Pay %s</h2><p>Reference %s</p>
                <p><a href="%ssuccess" style="display:block;background:#0BA4DB;color:#fff;padding:12px;text-align:center;text-decoration:none;border-radius:6px">Pay with test card ending 4412</a></p>
                <p><a href="%sdecline" style="display:block;border:1px solid #c33;color:#c33;padding:12px;text-align:center;text-decoration:none;border-radius:6px">Card declined</a></p>
                <p><a href="%sclose" style="display:block;color:#555;padding:12px;text-align:center">Close checkout</a></p>
                </body></html>""".formatted(Money.label(f.amountKobo(reference)), r, base, base, base);
    }

    /** success posts the signed charge.success webhook before the browser goes back (as Paystack usually does). */
    @GetMapping("/checkout/{reference}/{outcome}")
    public ResponseEntity<Void> outcome(@PathVariable String reference, @PathVariable String outcome) {
        FakeGateway f = fake();
        String back = f.callbackUrl(reference);
        if (back == null) throw ApiException.notFound();
        try {
            f.simulate(reference, outcome, "card", true);
        } catch (GatewayException e) {
            throw ApiException.badRequest("bad_outcome", e.getMessage());
        }
        String location = UriComponentsBuilder.fromUriString(back).queryParam("trxref", reference).queryParam("reference", reference).build().toUriString();
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    @PostMapping("/{reference}/simulate")
    public Simulated simulate(@PathVariable String reference, @RequestBody SimulateRequest in) {
        FakeGateway f = fake();
        try {
            List<FakeGateway.Webhook> hooks = f.simulate(reference, in.outcome() == null ? "SUCCESS" : in.outcome(), in.channel(),
                    in.webhook() == null || in.webhook());
            return new Simulated(reference, in.outcome(), hooks.stream().map(h -> new WebhookOut(h.event(), h.body(), h.signature())).toList());
        } catch (GatewayException e) {
            throw ApiException.badRequest("bad_outcome", e.getMessage());
        }
    }

    @PostMapping("/recheck")
    public RecheckResult recheck(@RequestBody(required = false) RecheckRequest in) {
        fake();
        int minutes = in == null || in.minutesAhead() == null ? 0 : in.minutesAhead();
        return recheck.run(clock.instant().plus(Duration.ofMinutes(minutes)));
    }
}
