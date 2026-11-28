package com.odel.payment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.odel.payment.client.FakeGateway;
import com.odel.payment.client.PaymentGateway;
import com.odel.payment.client.PaystackGateway;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** ODEL_PAYSTACK_MODE picks the gateway: paystack (needs ODEL_PAYSTACK_SECRET_KEY) or fake (the default, local and tests). */
@Configuration(proxyBeanMethods = false)
public class GatewayConfig {
    @Bean
    PaymentGateway paymentGateway(PaymentProperties props, ObjectMapper json, Clock clock,
                                  @Value("${server.port:9306}") int port) {
        if (props.fake()) {
            LoggerFactory.getLogger(GatewayConfig.class).warn("Payments use the FAKE gateway (ODEL_PAYSTACK_MODE=fake): nothing is charged.");
            return new FakeGateway(props, json, clock, "http://127.0.0.1:" + port);
        }
        return new PaystackGateway(props, json);
    }
}
