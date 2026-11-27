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
        AcademicLookup raw = directory.lookup();
        // academic-service gives a centre's name only; "Akure" (for "Akure Study Centre") is what lists show.
        AcademicLookup l = new AcademicLookup(raw.currentSession(), raw.programmes(), raw.centres().stream()
                .map(x -> x.shortName() != null ? x : new AcademicLookup.Centre(x.id(), x.code(), x.name(), shortName(x.name()), x.phone())).toList());
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
        String plain = k.replaceAll("\\s*(study\\s+)?cent(re|er)\\s*$", "").trim();
        return lookup().centres().stream().filter(c -> c.code().equalsIgnoreCase(k) || (c.name() != null && (c.name().equalsIgnoreCase(k)
                || c.name().toLowerCase(Locale.ROOT).replaceAll("\\s*(study\\s+)?cent(re|er)\\s*$", "").trim().equals(plain)))
                || (c.shortName() != null && c.shortName().equalsIgnoreCase(k))).findFirst();
    }

    static String shortName(String name) {
        if (name == null) return null;
        String s = name.replaceAll("(?i)\\s*(study\\s+)?cent(re|er)\\s*$", "").trim();
        return s.isEmpty() ? name : s;
    }

    public String sessionName() {
        AcademicLookup.Session s = lookup().currentSession();
        return s == null ? null : s.name();
    }
}
