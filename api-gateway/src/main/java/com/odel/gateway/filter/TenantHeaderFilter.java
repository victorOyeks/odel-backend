package com.odel.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out which university the browser is on from its host (X-Odel-Host from the web app's proxy, else Host)
 * and tells the services with X-Odel-Tenant-Id / -Kind. Whatever the client sent in those headers is dropped.
 * Answers are cached for a minute; unknown hosts pass through with no tenant (services answer unknown_host).
 */
@Component
public class TenantHeaderFilter implements GlobalFilter, Ordered {
    static final String HOST = "X-Odel-Host";
    static final String TENANT_ID = "X-Odel-Tenant-Id";
    static final String TENANT_KIND = "X-Odel-Tenant-Kind";
    private static final Resolved NONE = new Resolved(null, null);

    private final WebClient tenantService;
    private final String internalKey;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    record Resolved(String tenantId, String kind) {}

    record Cached(Resolved value, long at) {}

    public TenantHeaderFilter(@Value("${odel.services.tenant:http://127.0.0.1:9302}") String tenantUrl,
                              @Value("${odel.internal-api-key:dev-internal-key}") String internalKey) {
        this.tenantService = WebClient.create(tenantUrl);
        this.internalKey = internalKey;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String host = req.getHeaders().getFirst(HOST);
        if (host == null || host.isBlank()) host = req.getHeaders().getFirst("Host");
        String browserHost = host == null ? "" : host.trim().toLowerCase();
        return resolve(browserHost).flatMap(r -> chain.filter(exchange.mutate().request(req.mutate().headers(h -> {
            h.remove(TENANT_ID);
            h.remove(TENANT_KIND);
            h.set(HOST, browserHost);
            if (r.tenantId() != null) {
                h.set(TENANT_ID, r.tenantId());
                h.set(TENANT_KIND, r.kind());
            }
        }).build()).build()));
    }

    private Mono<Resolved> resolve(String host) {
        if (host.isEmpty()) return Mono.just(NONE);
        Cached c = cache.get(host);
        if (c != null && System.currentTimeMillis() - c.at() < 60_000) return Mono.just(c.value());
        return tenantService.get().uri(u -> u.path("/internal/hosts/resolve").queryParam("host", host).build())
                .header("X-Internal-Key", internalKey)
                .retrieve().bodyToMono(Map.class)
                .map(m -> new Resolved(String.valueOf(m.get("tenantId")), String.valueOf(m.get("kind"))))
                .timeout(Duration.ofSeconds(3))
                .doOnNext(r -> cache.put(host, new Cached(r, System.currentTimeMillis())))
                // "No such university" is an answer and is cached; tenant-service being down is not, so the next request asks again.
                .onErrorResume(WebClientResponseException.NotFound.class, e -> {
                    cache.put(host, new Cached(NONE, System.currentTimeMillis()));
                    return Mono.just(NONE);
                })
                .onErrorResume(e -> Mono.just(NONE));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
