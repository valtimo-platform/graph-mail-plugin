Throwaway certificates for the mTLS tests (GraphMailClientMtlsTest), valid for 100 years. Not used anywhere else.

- `ca.pem`: the CA the test server trusts for client certificates, and that signed `server.pem`
- `server.pem`, `server-key.pem`: the test server's certificate (localhost, 127.0.0.1)
- `client.pem`, `client-key.pem`: a client certificate signed by `ca.pem`
- `untrusted-client.pem`, `untrusted-client-key.pem`: a client certificate signed by another CA

The CA keys were not kept. To make new ones, run the sandbox's
`backend/app/imports/mtls-gateway/generate-certs.sh` and copy these files from its `certs` directory.
