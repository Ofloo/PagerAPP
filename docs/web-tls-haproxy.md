# Web HTTPS: HAProxy with an encrypted backend

This deployment terminates public HTTPS at HAProxy with Let's Encrypt and re-encrypts the
HAProxy → PagerAPP web connection with a private internal CA. Docker/Swarm secrets provide every
key; there are no volumes or host bind mounts.

## 1. Certificates

Create an offline root CA, then a server certificate for the **web service DNS name** (`web`).
The SAN must include `web`; add the Swarm service name if it differs.

```bash
openssl genrsa -out pagerapp-root-ca.key 4096
openssl req -x509 -new -nodes -key pagerapp-root-ca.key -sha256 -days 3650 \
  -subj '/CN=PagerAPP Internal CA' -out pagerapp-root-ca.crt

openssl genrsa -out pagerapp-web.key 2048
openssl req -new -key pagerapp-web.key -subj '/CN=web' -out pagerapp-web.csr
cat > pagerapp-web.ext <<'EXT'
subjectAltName=DNS:web
extendedKeyUsage=serverAuth
EXT
openssl x509 -req -in pagerapp-web.csr -CA pagerapp-root-ca.crt \
  -CAkey pagerapp-root-ca.key -CAcreateserial \
  -out pagerapp-web.crt -days 825 -sha256 -extfile pagerapp-web.ext
```

Keep `pagerapp-root-ca.key` offline. HAProxy only needs the **public** root certificate; the web
container needs the server certificate and private key.

For Let's Encrypt at HAProxy, create a combined PEM secret (HAProxy's `crt` expects cert + key in
one file):

```bash
cat letsencrypt-fullchain.pem letsencrypt-privkey.pem > haproxy-le.pem
```

## 2. Swarm secrets

```bash
docker secret create pagerapp_tls_cert ./pagerapp-web.crt
docker secret create pagerapp_tls_key  ./pagerapp-web.key
docker secret create pagerapp_internal_ca ./pagerapp-root-ca.crt
docker secret create haproxy_le ./haproxy-le.pem
```

The web image automatically reads `/run/secrets/pagerapp_tls_cert` and
`/run/secrets/pagerapp_tls_key`; `PORT=8443` selects HTTPS. No TLS env vars or volumes are needed.

## 3. Deploy

```bash
docker stack deploy -c docker-stack.web-tls.yml pagerapp
```

Only HAProxy publishes `80/443`. The web service has no published port. HAProxy's backend uses:

```haproxy
server web web:8443 ssl verify required ca-file /run/secrets/pagerapp_internal_ca check
```

That verifies the instance certificate against your root CA, so page/chat bodies are encrypted on
the internal hop as well.

## 4. Verify

```bash
curl -fsS https://pagerapp.example/api/health
curl -kfsS https://web:8443/api/health   # from inside the overlay network
```

`GET /api/health` returns `tls:true` when the instance itself is HTTPS.

For public clients use the Let's Encrypt hostname; never expose the internal `web:8443` port.
Cloudflare Tunnel is not needed in this layout. If it is used instead of direct HAProxy, point its
origin at the HAProxy HTTPS listener and do not set `noTLSVerify` against a trusted Let's Encrypt
certificate.
