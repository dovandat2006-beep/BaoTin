#!/bin/sh
set -eu
umask 077
for directory in auth-config auth-keys gateway-config; do
  test -d "/source/$directory"
  mkdir -p "/target/$directory"
  # Only regular source files; never follow a link into another host directory.
  for file in "/source/$directory/"*; do
    test -f "$file" && test ! -L "$file" || exit 1
    name=${file##*/}
    case "$name" in *[!A-Za-z0-9._-]*) exit 1;; esac
    if test -f "/target/$directory/$name"; then
      cmp -s "$file" "/target/$directory/$name" || { echo 'Secret replacement needs a reviewed rotation' >&2; exit 1; }
    else
      cp "$file" "/target/$directory/$name"
    fi
    chmod 0400 "/target/$directory/$name"
    chown 1001:1001 "/target/$directory/$name"
  done
  chown 1001:1001 "/target/$directory"
  chmod 0700 "/target/$directory"
done
echo 'ALT_SECRET_VOLUME_READY scoped_mounts=true linux_uid=1001 values_logged=false'
