package com.odel.tenancy;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The university the current request or job acts for. Hibernate reads it (TenantIdResolver) and
 * adds "tenant_id = ?" to every query on tenant-scoped entities, and sets it on every insert.
 * This is the one place tenant filtering happens.
 */
public final class TenantContext {
    public static final UUID PLATFORM_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** Matches no row: used when nothing has set a tenant, so a forgotten context returns nothing. */
    public static final UUID NONE = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static UUID current() {
        UUID id = CURRENT.get();
        return id == null ? NONE : id;
    }

    public static UUID require() {
        UUID id = CURRENT.get();
        if (id == null) throw new IllegalStateException("No tenant in context");
        return id;
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs work as another tenant (provider set-up, background jobs). Start a new transaction inside. */
    public static <T> T runAs(UUID tenantId, Supplier<T> work) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static void runAs(UUID tenantId, Runnable work) {
        runAs(tenantId, () -> { work.run(); return null; });
    }
}
