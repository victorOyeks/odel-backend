package com.odel.payment.devseed;

import com.odel.payment.client.PeerClient;
import com.odel.payment.config.PaymentProperties;
import com.odel.payment.service.ProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dev only: the university with slug ODEL_PAYMENT_SEED_SLUG (wesley) gets a settlement account and subaccount if it has none, so
 * payments can be tried at once: Paystack's test bank in paystack (test) mode, a fake code in fake mode. Tries every 10 minutes
 * until the university exists; a university that has one is never changed.
 */
@Component
@Profile("dev")
public class DevAccountSeed {
    private static final Logger log = LoggerFactory.getLogger(DevAccountSeed.class);
    private final PeerClient peers;
    private final ProviderService provider;
    private final PaymentProperties props;
    private volatile boolean done;

    public DevAccountSeed(PeerClient peers, ProviderService provider, PaymentProperties props) {
        this.peers = peers;
        this.provider = provider;
        this.props = props;
    }

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT10M")
    public void seed() {
        if (done || props.seed() == null || props.seed().slug() == null || props.seed().slug().isBlank()) return;
        try {
            peers.universities().stream().filter(t -> props.seed().slug().equalsIgnoreCase(t.slug())).findFirst().ifPresent(t -> {
                provider.seedAccount(t.id(), t.name(), props.seed().bankCode(), props.seed().accountNumber());
                done = true;
                log.info("Dev seed: {} has a settlement account for payments", t.name());
            });
        } catch (Exception e) {
            log.warn("Dev seed of the payment account did not run yet: {}", e.getMessage());
        }
    }
}
