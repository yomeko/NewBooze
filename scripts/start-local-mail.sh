#!/bin/sh
# このPC内だけでメールを受け取り、外部へは配信しない。
set -eu
if ! command -v mailpit >/dev/null 2>&1; then
  echo 'Mailpitが必要です。macOS: brew install mailpit' >&2
  exit 1
fi
exec mailpit --smtp 127.0.0.1:1025 --listen 127.0.0.1:8025
