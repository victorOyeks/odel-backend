package com.odel.tenant.controller;

import com.odel.common.ApiException;
import com.odel.common.files.StoredFiles;
import com.odel.common.tenancy.ResolvedHost;
import com.odel.tenant.entity.Tenant;
import com.odel.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PublicLogoTest {
    private final StoredFiles files = mock(StoredFiles.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final PublicContextController c = new PublicContextController(files, null, tenants);
    private final Tenant wesley = new Tenant();
    private final UUID otherUni = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        wesley.setId(UUID.randomUUID());
        wesley.setLogoKey("logos/8a9051bd-2b00-4cb1-986a-486c97408605.png");
        when(tenants.findById(wesley.getId())).thenReturn(Optional.of(wesley));
        when(files.read(eq(wesley.getLogoKey()), anyLong())).thenReturn(new byte[]{1, 2, 3});
    }

    private MockHttpServletRequest on(UUID tenantId, String kind) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute(ResolvedHost.REQUEST_ATTRIBUTE, new ResolvedHost("x", tenantId, kind));
        return r;
    }

    private String file() {
        String path = PublicContextController.logoPath(wesley);
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Test
    void stableVersionedAddressCachedForAYear() {
        assertThat(PublicContextController.logoPath(wesley)).isEqualTo(PublicContextController.logoPath(wesley)).matches("/api/v1/public/logos/[0-9a-f-]{36}/[0-9a-f]{12}\\.png");
        var res = c.logo(on(wesley.getId(), "UNIVERSITY"), wesley.getId(), file(), null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType()).hasToString("image/png");
        assertThat(res.getHeaders().getCacheControl()).isEqualTo("public, max-age=31536000, immutable");
        assertThat(res.getHeaders().getETag()).isNotBlank();
        assertThat(res.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");
        assertThat(res.getBody()).containsExactly(1, 2, 3);
    }

    @Test
    void sameETagAnswers304WithoutReadingTheFile() {
        String etag = c.logo(on(null, "PLATFORM"), wesley.getId(), file(), null).getHeaders().getETag();
        clearInvocations(files);
        var res = c.logo(on(null, "PLATFORM"), wesley.getId(), file(), etag);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        verifyNoInteractions(files);
    }

    @Test
    void aNewLogoGetsANewAddressAndTheOldOneIs404() {
        String old = file();
        wesley.setLogoKey("logos/1b2c3d4e-0000-4000-8000-000000000000.svg");
        assertThat(file()).isNotEqualTo(old).endsWith(".svg");
        assertThatThrownBy(() -> c.logo(on(wesley.getId(), "UNIVERSITY"), wesley.getId(), old, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void unknownUniversityOrAnotherUniversitysAddressIs404() {
        assertThatThrownBy(() -> c.logo(on(null, "PLATFORM"), UUID.randomUUID(), file(), null)).isInstanceOf(ApiException.class);
        // On another university's web address, Wesley's logo is not served.
        assertThatThrownBy(() -> c.logo(on(otherUni, "UNIVERSITY"), wesley.getId(), file(), null)).isInstanceOf(ApiException.class);
    }
}
