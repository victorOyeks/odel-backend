package com.odel.registration.client;

import com.odel.common.OdelProperties;
import com.odel.common.internal.InternalClient;
import com.odel.common.tenancy.TenantContext;
import com.odel.registration.dto.PeerDtos.TenantLook;
import com.odel.registration.dto.PeerDtos.TenantView;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The university's name, colour, portal address and crest (for emails, links and the slip), cached for a minute. */
@Component
public class TenantClient {
    private final InternalClient internal;
    private final OdelProperties props;
    private final RestClient http = RestClient.create();
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> logos = new ConcurrentHashMap<>();

    record Cached(TenantView tenant, long at) {}

    public TenantClient(InternalClient internal, OdelProperties props) {
        this.internal = internal;
        this.props = props;
    }

    public TenantView tenant() {
        UUID id = TenantContext.require();
        Cached c = cache.get(id);
        if (c != null && System.currentTimeMillis() - c.at() < 60_000) return c.tenant();
        TenantView t = internal.get(InternalClient.Service.TENANT, "/internal/tenants/" + id, TenantView.class);
        cache.put(id, new Cached(t, System.currentTimeMillis()));
        return t;
    }

    public TenantLook look() {
        TenantView t = tenant();
        return new TenantLook(t.name(), t.shortName(), t.primaryColor() == null ? "#1A2368" : t.primaryColor());
    }

    /** The portal's address without a trailing slash ("https://wesley.odel.ng"), or "" when unknown. */
    public String portal() {
        String p = tenant().portalUrl();
        return p == null ? "" : p.replaceAll("/+$", "");
    }

    /** The crest's bytes (PNG or JPEG), or null when the university has none or it cannot be read. SVG is not drawn. */
    public byte[] logo() {
        String path = tenant().logoUrl();
        if (path == null || path.isBlank() || path.toLowerCase().endsWith(".svg")) return null;
        return logos.computeIfAbsent(path, p -> {
            try {
                String url = p.startsWith("http") ? p : props.services().tenant() + p;
                byte[] b = http.get().uri(url).retrieve().body(byte[].class);
                return b == null ? new byte[0] : b;
            } catch (RuntimeException e) {
                return new byte[0];
            }
        }).length == 0 ? null : logos.get(path);
    }
}
