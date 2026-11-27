# Server log retention (all ODeL services)

Every ODeL service runs as a systemd unit and writes to the systemd journal, so one journald setting caps them all.

| What | Where on the server | Rule |
|---|---|---|
| Service logs (journal) | `/etc/systemd/journald.conf.d/odel-retention.conf` (this folder's `journald-odel-retention.conf`) | kept 3 days, at most 1 GB, always leaves 10 GB of disk free; one file per day |
| syslog, auth, kern, mail, user, cron | `/etc/logrotate.d/rsyslog` | rotated daily (or at 200 MB), 3 kept, compressed |
| nginx access/error | `/etc/logrotate.d/nginx` | daily, 3 kept, compressed |

Apply on a new server (as root):

```sh
mkdir -p /etc/systemd/journald.conf.d
cp journald-odel-retention.conf /etc/systemd/journald.conf.d/odel-retention.conf
systemctl restart systemd-journald && journalctl --vacuum-time=3d --vacuum-size=1G
sed -i -E 's/^(\s*)weekly/\1daily/; s/^(\s*)rotate [0-9]+/\1rotate 3/' /etc/logrotate.d/rsyslog
grep -q maxsize /etc/logrotate.d/rsyslog || sed -i -E '0,/\{/s//{\n\tmaxsize 200M/' /etc/logrotate.d/rsyslog
sed -i -E 's/^(\s*)rotate [0-9]+/\1rotate 3/' /etc/logrotate.d/nginx
logrotate -d /etc/logrotate.conf   # check the config
```

To read older logs than 3 days you can't — they're deleted by design. Read recent ones with `journalctl -u odel-<service> --since "2 days ago"`.

On the dev box (shared with CoRyd dev) the same rule is already in force from the Hospio staging days (`/etc/systemd/journald.conf.d/hospio-retention.conf`); the ODeL file is identical, so either name works.

What the logs hold (per service, `sudo journalctl -u odel-<service>`):
- one line per API call: `<<< METHOD /path -> status (ms) | user: <id> <ROLE> | tenant: <id>`, with a request id `[reqId]` on every line of that call;
- with `ODEL_LOG_BODIES=true` (dev): the request body of every non-GET call and the body of every error response, secrets masked.
Who did what (sign-ins, role changes, university changes, domain changes) is in the audit log (`app.audit_log`, console → Audit trail), kept 7 years.
