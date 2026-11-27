package com.odel.tenancy;

/** What the browser's host points at: a university portal, the provider console, or nothing. */
public record ResolvedHost(String host, Tenant tenant) {
    public static final String REQUEST_ATTRIBUTE = "odel.resolvedHost";

    public boolean known() { return tenant != null; }
    public boolean isPlatform() { return tenant != null && tenant.isPlatform(); }
    public boolean isUniversity() { return tenant != null && !tenant.isPlatform(); }
}
