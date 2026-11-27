package com.odel.student.service;

import com.odel.common.tenancy.TenantContext;
import com.odel.student.client.AcademicDirectory;
import com.odel.student.dto.AcademicLookup;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The current university's programmes and centres, cached for 30 seconds (an import asks once; the list asks often). */
@Service
public class AcademicLookupService {
    private final AcademicDirectory directory;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    record Cached(AcademicLookup lookup, long at) {}

    public AcademicLookupService(AcademicDirectory directory) {
        this.directory = directory;
    }

    public AcademicLookup lookup() {
        UUID t = TenantContext.require();
        Cached c = cache.get(t);
        if (c != null && System.currentTimeMillis() - c.at() < 30_000) return c.lookup();
        AcademicLookup l = directory.lookup();
        cache.put(t, new Cached(l, System.currentTimeMillis()));
        return l;
    }

    /** A fresh read (an import is checked against what academic-service says now). */
    public AcademicLookup fresh() {
        cache.remove(TenantContext.require());
        return lookup();
    }

    public Optional<AcademicLookup.Programme> programme(String code) {
        if (code == null) return Optional.empty();
        return lookup().programmes().stream().filter(p -> p.code().equalsIgnoreCase(code.trim())).findFirst();
    }

    public Optional<AcademicLookup.Centre> centre(String codeOrName) {
        if (codeOrName == null) return Optional.empty();
        String k = codeOrName.trim().toLowerCase(Locale.ROOT);
        return lookup().centres().stream().filter(c -> c.code().equalsIgnoreCase(k) || (c.name() != null && c.name().equalsIgnoreCase(k))
                || (c.shortName() != null && c.shortName().equalsIgnoreCase(k))).findFirst();
    }

    public String sessionName() {
        AcademicLookup.Session s = lookup().currentSession();
        return s == null ? null : s.name();
    }
}
