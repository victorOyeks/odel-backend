package com.odel.tenant.controller;

import com.odel.tenant.dto.PublicDtos.*;

import com.odel.tenant.entity.Tenant;
import com.odel.tenant.repository.TenantRepository;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.odel.common.ApiException;
import com.odel.common.OdelProperties;
import com.odel.common.files.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Branding before sign-in: each university's pages load their own name, colours and logo. */
@RestController
@RequestMapping("/api/v1/public")
public class PublicContextController {
    static final long MAX_LOGO_BYTES = 2L * 1024 * 1024;
    private final StoredFiles files;
    private final OdelProperties props;
    private final TenantRepository tenants;

    public PublicContextController(StoredFiles files, OdelProperties props, TenantRepository tenants) {
        this.files = files;
        this.props = props;
        this.tenants = tenants;
    }

    @GetMapping("/context")
    public Context context(HttpServletRequest request) {
        ResolvedHost host = TenantFilter.resolved(request);
        Tenant t = host.known() ? tenants.findById(host.tenantId()).orElse(null) : null;
        if (t == null) throw new ApiException(HttpStatus.NOT_FOUND, "unknown_host", "This web address is not set up on the platform.");
        return new Context(t.getKind().name(), branding(t), props.google().devStub());
    }

    public TenantBranding branding(Tenant t) {
        String logo = logoPath(t);
        return new TenantBranding(t.getId(), t.getSlug(), t.getName(), t.getShortName(), t.getPrimaryColor(),
                t.getAccentColor(), logo, t.getGoogleDomain(), t.getStatus().name());
    }

    /**
     * The logo's stable, cacheable address: it names the university and a short hash of the stored file, so a new logo
     * gets a new address and the browser may keep each one for a year (a signed link changed on every page, so the crest
     * was fetched again after each page had shown).
     */
    public static String logoPath(Tenant t) {
        String key = t.getLogoKey();
        if (key == null || key.isBlank()) return null;
        return "/api/v1/public/logos/" + t.getId() + "/" + version(key) + key.substring(key.lastIndexOf('.'));
    }

    static String version(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 6);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The logo itself. On a university's address only its own logo; the console may show any university's. */
    @GetMapping("/logos/{tenantId}/{file:.+}")
    public ResponseEntity<byte[]> logo(HttpServletRequest request, @PathVariable UUID tenantId, @PathVariable String file,
                                       @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        ResolvedHost host = TenantFilter.resolved(request);
        if (host.isUniversity() && !tenantId.equals(host.tenantId())) throw ApiException.notFound();
        Tenant t = tenants.findById(tenantId).orElseThrow(ApiException::notFound);
        String path = logoPath(t);
        if (path == null || !path.endsWith("/" + file)) throw ApiException.notFound();
        String etag = "\"" + version(t.getLogoKey()) + "\"";
        HttpHeaders h = new HttpHeaders();
        h.setCacheControl("public, max-age=31536000, immutable");
        h.setETag(etag);
        h.set("X-Content-Type-Options", "nosniff");
        // An SVG can carry script: it is shown as an image only, never run as a page.
        h.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
        if (etag.equals(ifNoneMatch)) return new ResponseEntity<>(h, HttpStatus.NOT_MODIFIED);
        h.setContentType(file.endsWith(".svg") ? MediaType.valueOf("image/svg+xml") : MediaType.IMAGE_PNG);
        return new ResponseEntity<>(files.read(t.getLogoKey(), MAX_LOGO_BYTES), h, HttpStatus.OK);
    }
}
