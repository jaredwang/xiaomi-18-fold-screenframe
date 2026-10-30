#!/usr/bin/env bash
# One-time setup for signed GitHub releases.
#
# Creates a release keystore under ~/.android (unless one exists) and stores
# it plus its password in this repo's GitHub Actions secrets, which the
# "Release APK" workflow uses to sign every release with the same key.
#
# Back up the .jks and .properties files it creates: without them, future
# releases can't be installed over existing ones.
set -euo pipefail

KS="$HOME/.android/fold18-screenframe-release.jks"
PROPS="$HOME/.android/fold18-screenframe-release.properties"
ALIAS="screenframe"

command -v gh >/dev/null || { echo "Install the GitHub CLI (gh) and run 'gh auth login' first." >&2; exit 1; }
KEYTOOL="$(command -v keytool || true)"
if [[ -z "$KEYTOOL" && -x /usr/libexec/java_home ]]; then KEYTOOL="$(/usr/libexec/java_home -v 17)/bin/keytool"; fi
[[ -x "$KEYTOOL" ]] || { echo "keytool not found; install JDK 17." >&2; exit 1; }

mkdir -p "$HOME/.android"
umask 077

if [[ -f "$KS" ]]; then
  echo "Using existing keystore $KS"
  PW="$(sed -n 's/^storePassword=//p' "$PROPS")"
else
  PW="$(openssl rand -base64 32 | tr -d '/+=' | cut -c1-24)"
  "$KEYTOOL" -genkeypair -keystore "$KS" -storetype PKCS12 -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PW" -keypass "$PW" -dname "CN=Screen Frame" >/dev/null
  printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' "$KS" "$PW" "$ALIAS" "$PW" > "$PROPS"
  echo "Created $KS"
fi

cd "$(dirname "$0")/../.."
base64 < "$KS" | tr -d '\n' | gh secret set SIGNING_KEYSTORE_BASE64
printf '%s' "$PW" | gh secret set SIGNING_STORE_PASSWORD
printf '%s' "$PW" | gh secret set SIGNING_KEY_PASSWORD
printf '%s' "$ALIAS" | gh secret set SIGNING_KEY_ALIAS

echo
echo "Done. Secrets set for $(gh repo view --json nameWithOwner --jq .nameWithOwner)."
echo "Back up these two files somewhere safe:"
echo "  $KS"
echo "  $PROPS"
