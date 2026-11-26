#!/bin/sh
set -eu
umask 077
export LC_ALL=C
password=$(cat /run/secrets/redis-password)
[ "${#password}" -ge 8 ] && [ "${#password}" -le 128 ] || exit 1
# Config-tree and Redis must use identical bytes; reject trailing newline/extra content.
[ "$(wc -c < /run/secrets/redis-password)" -eq "${#password}" ] || exit 1
[ "$(printf '%s' "$password" | tr -d '\r\n' | wc -c)" -eq "${#password}" ] || exit 1
hash=$(printf '%s' "$password" | sha256sum | cut -d' ' -f1)
unset password
# Only Gateway's actual rate-limit namespace/commands, including Lettuce's handshake.
printf '%s\n' "user default on #$hash ~gateway:* +ping +info +hello +auth +client|setinfo +client|setname +client|id +get +set +incr +expire +ttl +del +eval +evalsha +script|load +script|exists" > /tmp/baotin-users.acl
cat > /tmp/baotin-redis.conf <<'EOF'
bind 0.0.0.0
protected-mode yes
port 6379
dir /data
appendonly yes
appendfsync everysec
save ""
maxmemory 64mb
maxmemory-policy noeviction
aclfile /tmp/baotin-users.acl
EOF
exec redis-server /tmp/baotin-redis.conf
