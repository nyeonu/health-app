#!/usr/bin/env bash
# Run in the owner's private terminal, never in Actions or a shared chat.
set -euo pipefail
command -v keytool >/dev/null || { echo 'A JDK with keytool is required.' >&2; exit 1; }
SIGNING_DIR="${HEALTH_SIGNING_DIR:-$HOME/.health-app-signing}"
umask 077
mkdir -p "$SIGNING_DIR"
KEYSTORE="$SIGNING_DIR/healthapp-signing.p12"
ENCODED="$SIGNING_DIR/healthapp-signing.keystore.base64"
if [ -e "$KEYSTORE" ] || [ -e "$ENCODED" ]; then
  echo 'Signing material already exists. Reuse and back it up; do not generate a replacement.' >&2
  exit 1
fi
echo 'Choose a strong keystore password and keep it in your password manager.'
keytool -genkeypair -storetype PKCS12 -keystore "$KEYSTORE" \
  -alias healthapp -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Health App'
base64 < "$KEYSTORE" | tr -d '\n' > "$ENCODED"
chmod 600 "$KEYSTORE" "$ENCODED"
echo 'Created outside the repository:'
printf '%s\n%s\n' "$KEYSTORE" "$ENCODED"
echo 'Keep a private backup of the p12 and password. The Base64 file is also secret.'
echo 'Use the Base64 file as ANDROID_SIGNING_KEYSTORE_BASE64 and the password as ANDROID_SIGNING_PASSWORD.'
echo 'Never post either value in chat, a commit, an issue, a screenshot or an Actions log.'
