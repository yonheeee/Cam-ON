#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SECRETS_DIR="${SCRIPT_DIR}/secrets"
PRIVATE_KEY="${SECRETS_DIR}/jwt-private.pem"
PUBLIC_KEY="${SECRETS_DIR}/jwt-public.pem"

umask 077
mkdir -p "${SECRETS_DIR}"

if [ -e "${PRIVATE_KEY}" ] || [ -e "${PUBLIC_KEY}" ]; then
    echo "JWT key file already exists. Existing keys were not changed." >&2
    exit 1
fi

openssl genpkey \
    -algorithm RSA \
    -pkeyopt rsa_keygen_bits:2048 \
    -out "${PRIVATE_KEY}"
openssl pkey \
    -in "${PRIVATE_KEY}" \
    -pubout \
    -out "${PUBLIC_KEY}"

chmod 600 "${PRIVATE_KEY}"
chmod 644 "${PUBLIC_KEY}"
echo "JWT keys created in ${SECRETS_DIR}"
