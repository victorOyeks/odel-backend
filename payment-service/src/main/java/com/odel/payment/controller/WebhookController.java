package com.odel.payment.controller;

import com.odel.payment.service.WebhookService;
import org.springframework.web.bind.annotation.*;

/** Paystack's webhook: public (no session, no CSRF), the HMAC-SHA512 signature over the raw body is what lets it in. */
@RestController
@RequestMapping("/api/v1/payments/webhooks")
public class WebhookController {
    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/paystack")
    public WebhookService.Result paystack(@RequestBody byte[] body,
                                          @RequestHeader(value = "x-paystack-signature", required = false) String signature) {
        return webhooks.handle(body, signature);
    }
}
