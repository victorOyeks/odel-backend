package com.odel.payment.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Each university's fee, centre share and settlement account (fee_settings). Plain JDBC with the tenant id passed explicitly:
 * the provider acts on any university from the console, and the row is keyed by the tenant itself.
 */
@Repository
public class FeeSettingsRepository {
    public record Settings(UUID tenantId, long feeKobo, long centreShareKobo, String bankCode, String bankName, String accountLast4,
                           String accountName, String subaccountCode, Instant accountSince, UUID accountUpdatedBy, String accountUpdatedByName,
                           Instant updatedAt) {
        public boolean paymentsOpen() {
            return subaccountCode != null && !subaccountCode.isBlank();
        }
    }

    private final JdbcTemplate jdbc;

    private static final RowMapper<Settings> ROW = (rs, i) -> new Settings(rs.getObject("tenant_id", UUID.class), rs.getLong("fee_kobo"),
            rs.getLong("centre_share_kobo"), rs.getString("bank_code"), rs.getString("bank_name"), rs.getString("account_last4"),
            rs.getString("account_name"), rs.getString("subaccount_code"), instant(rs.getTimestamp("account_since")),
            rs.getObject("account_updated_by", UUID.class), rs.getString("account_updated_by_name"), instant(rs.getTimestamp("updated_at")));

    public FeeSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    public Optional<Settings> find(UUID tenantId) {
        return jdbc.query("select * from fee_settings where tenant_id = ?", ROW, tenantId).stream().findFirst();
    }

    public List<Settings> all() {
        return jdbc.query("select * from fee_settings", ROW);
    }

    public void saveAmounts(UUID tenantId, long feeKobo, long centreShareKobo, Instant now) {
        jdbc.update("""
                insert into fee_settings (tenant_id, fee_kobo, centre_share_kobo, updated_at) values (?, ?, ?, ?)
                on conflict (tenant_id) do update set fee_kobo = excluded.fee_kobo, centre_share_kobo = excluded.centre_share_kobo,
                    updated_at = excluded.updated_at""", tenantId, feeKobo, centreShareKobo, Timestamp.from(now));
    }

    /** Stores the settlement account (last four digits only) and its subaccount code, creating the row with the defaults if needed. */
    public void saveAccount(UUID tenantId, long defaultFeeKobo, long defaultShareKobo, String bankCode, String bankName, String last4,
                            String accountName, String subaccountCode, UUID by, String byName, Instant now) {
        jdbc.update("""
                insert into fee_settings (tenant_id, fee_kobo, centre_share_kobo, bank_code, bank_name, account_last4, account_name,
                    subaccount_code, account_since, account_updated_by, account_updated_by_name, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id) do update set bank_code = excluded.bank_code, bank_name = excluded.bank_name,
                    account_last4 = excluded.account_last4, account_name = excluded.account_name, subaccount_code = excluded.subaccount_code,
                    account_since = excluded.account_since, account_updated_by = excluded.account_updated_by,
                    account_updated_by_name = excluded.account_updated_by_name, updated_at = excluded.updated_at""",
                tenantId, defaultFeeKobo, defaultShareKobo, bankCode, bankName, last4, accountName, subaccountCode, Timestamp.from(now), by, byName,
                Timestamp.from(now));
    }
}
