#!/usr/bin/env bash

set -euo pipefail

: "${TELEGRAM_BOT_TOKEN:?TELEGRAM_BOT_TOKEN is required}"
: "${TELEGRAM_CHAT_ID:?TELEGRAM_CHAT_ID is required}"
: "${RELEASE_TAG:?RELEASE_TAG is required}"
: "${RELEASE_URL:?RELEASE_URL is required}"
: "${RELEASE_NOTES_ZH_FILE:?RELEASE_NOTES_ZH_FILE is required}"
: "${ONLINE_APK:?ONLINE_APK is required}"
: "${OFFLINE_APK:?OFFLINE_APK is required}"

for file in "$ONLINE_APK" "$OFFLINE_APK" "$RELEASE_NOTES_ZH_FILE"; do
  if [[ ! -f "$file" ]]; then
    echo "::error::文件不存在: $file"
    exit 1
  fi
done

API_BASE="https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}"

telegram_request() {
  local method="$1"
  shift
  local response

  response="$(curl --fail-with-body -sS -X POST "$API_BASE/$method" "$@")"
  if ! jq -e '.ok == true' >/dev/null <<<"$response"; then
    echo "::error::Telegram API $method 失败: $response"
    exit 1
  fi
}

RELEASE_NOTES_ZH="$(<"$RELEASE_NOTES_ZH_FILE")"
ANNOUNCEMENT_HEADER="${ANNOUNCEMENT_HEADER:-}"

if [[ -n "$ANNOUNCEMENT_HEADER" ]]; then
  CAPTION="$(printf '%s\n\n下载地址：%s\n\n更新日志：\n%s' \
    "$ANNOUNCEMENT_HEADER" "$RELEASE_URL" "$RELEASE_NOTES_ZH")"
else
  CAPTION="$(printf '下载地址：%s\n\n更新日志：\n%s' \
    "$RELEASE_URL" "$RELEASE_NOTES_ZH")"
fi

if (( ${#CAPTION} > 1024 )); then
  echo "::error::中文更新日志过长，Telegram 媒体 Caption 超过 1024 个字符；请精简更新日志"
  exit 1
fi

MEDIA_JSON="$(jq -cn \
  --arg caption "$CAPTION" \
  '[
    {"type":"document","media":"attach://online","caption":$caption},
    {"type":"document","media":"attach://offline"}
  ]')"

# Send both APKs and the Chinese release notes as one media group.
telegram_request sendMediaGroup \
  --form-string "chat_id=$TELEGRAM_CHAT_ID" \
  --form-string "media=$MEDIA_JSON" \
  -F "online=@$ONLINE_APK" \
  -F "offline=@$OFFLINE_APK"

echo "Telegram release announcement sent for $RELEASE_TAG"
