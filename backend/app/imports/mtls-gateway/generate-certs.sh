#!/usr/bin/env bash
#
# Generates throwaway certificates for the local mTLS gateway (docker-compose service 'mtls-gateway') and writes
# them, together with the plugin configurations that use them, to:
#   - certs/ca.pem, certs/ca-key.pem          : CA that signs the gateway's and the client certificate
#   - certs/server.pem, certs/server-key.pem  : certificate the gateway presents (localhost, 127.0.0.1)
#   - certs/client.pem, certs/client-key.pem  : client certificate Graph Mail presents to the gateway
#   - certs/untrusted-client.pem, certs/untrusted-client-key.pem
#                                             : client certificate of another CA, which the gateway refuses
#   - src/main/resources/config/plugin/graph-mail.pluginconfig.json: the certificates of the Http Client
#     Authentication configurations in it
#
# For local testing only. The certificates are committed, so the sandbox works without running this. Run from
# anywhere: ./backend/app/imports/mtls-gateway/generate-certs.sh

set -euo pipefail

GATEWAY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$GATEWAY_DIR/../.." && pwd)"
CERTS_DIR="$GATEWAY_DIR/certs"
PLUGIN_CONFIG="$APP_DIR/src/main/resources/config/plugin/graph-mail.pluginconfig.json"
DAYS=36500
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

mkdir -p "$CERTS_DIR"
cd "$CERTS_DIR"

openssl req -x509 -newkey rsa:2048 -nodes -days "$DAYS" -keyout ca-key.pem -out ca.pem \
  -subj "/CN=Graph Mail sandbox gateway CA"
openssl req -x509 -newkey rsa:2048 -nodes -days "$DAYS" -keyout "$WORK_DIR/other-ca-key.pem" \
  -out "$WORK_DIR/other-ca.pem" -subj "/CN=Graph Mail sandbox untrusted CA"

cat > "$WORK_DIR/server.ext" <<EXT
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:localhost, DNS:mtls-gateway, IP:127.0.0.1
EXT
cat > "$WORK_DIR/client.ext" <<EXT
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth
EXT

openssl req -newkey rsa:2048 -nodes -keyout server-key.pem -out "$WORK_DIR/server.csr" -subj "/CN=localhost"
openssl x509 -req -in "$WORK_DIR/server.csr" -CA ca.pem -CAkey ca-key.pem -CAcreateserial -CAserial "$WORK_DIR/ca.srl" \
  -days "$DAYS" -extfile "$WORK_DIR/server.ext" -out server.pem

openssl req -newkey rsa:2048 -nodes -keyout client-key.pem -out "$WORK_DIR/client.csr" -subj "/CN=graph-mail-sandbox-client"
openssl x509 -req -in "$WORK_DIR/client.csr" -CA ca.pem -CAkey ca-key.pem -CAserial "$WORK_DIR/ca.srl" \
  -days "$DAYS" -extfile "$WORK_DIR/client.ext" -out client.pem

openssl req -newkey rsa:2048 -nodes -keyout untrusted-client-key.pem -out "$WORK_DIR/untrusted.csr" \
  -subj "/CN=graph-mail-sandbox-untrusted-client"
openssl x509 -req -in "$WORK_DIR/untrusted.csr" -CA "$WORK_DIR/other-ca.pem" -CAkey "$WORK_DIR/other-ca-key.pem" \
  -CAcreateserial -CAserial "$WORK_DIR/other-ca.srl" -days "$DAYS" -extfile "$WORK_DIR/client.ext" -out untrusted-client.pem

python3 - "$CERTS_DIR" "$PLUGIN_CONFIG" <<'PY'
import json, pathlib, sys

certs = pathlib.Path(sys.argv[1])
pem = lambda name: (certs / name).read_text()
# Per configuration title: its certificates. The gateway's CA is trusted by all of them.
# The other configurations ("Not a Graph login", "Microsoft direct") have no certificates.
tls = {
    "Graph login via gateway": {"clientCertificate": pem("client.pem"), "clientPrivateKey": pem("client-key.pem")},
    "Graph login without client certificate": {},
    "Graph login, untrusted client certificate": {
        "clientCertificate": pem("untrusted-client.pem"),
        "clientPrivateKey": pem("untrusted-client-key.pem"),
    },
}
config_file = pathlib.Path(sys.argv[2])
configs = json.loads(config_file.read_text())
for config in configs:
    if config["title"] in tls:
        config["properties"].update({"serverCertificate": pem("ca.pem"), **tls[config["title"]]})
config_file.write_text(json.dumps(configs, indent=2) + "\n")
PY

echo "Certificates written to $CERTS_DIR"
echo "Plugin configurations written to $PLUGIN_CONFIG"
