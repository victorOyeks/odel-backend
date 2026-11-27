package com.odel.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * One line per call through the gateway: method, path, status, time, the browser's host and the client address.
 * The services log the same call with the user and university; this one shows everything that came in, even what
 * never reached a service (unknown routes, a service restarting).
 */
@Component
public class AccessLogFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        ServerHttpRequest req = exchange.getRequest();
        String path = req.getPath().value();
        if (path.startsWith("/actuator")) return chain.filter(exchange);
        return chain.filter(exchange).doFinally(signal -> {
            var status = exchange.getResponse().getStatusCode();
            int code = status == null ? 0 : status.value();
            String host = req.getHeaders().getFirst(TenantHeaderFilter.HOST);
            String fwd = req.getHeaders().getFirst("X-Forwarded-For");
            String ip = fwd != null ? fwd.split(",")[0].trim() : req.getRemoteAddress() == null ? "-" : req.getRemoteAddress().getAddress().getHostAddress();
            String line = "<<< {} {} -> {} ({}ms) | host: {} | ip: {}";
            Object[] args = {req.getMethod(), path, code, System.currentTimeMillis() - start, host == null ? "-" : host, ip};
            if (code >= 500 || code == 0) log.error(line, args);
            else if (code >= 400) log.warn(line, args);
            else log.info(line, args);
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
