#!/bin/sh
# Generates a POC certificate authority, the cloud broker server cert and the
# plant edge bridge client cert (mutual TLS). Runs once in the certs-init container.
set -e
cd /certs
if [ -f ca.crt ] && [ -f cloud-mqtt.crt ] && [ -f edge-bridge.crt ]; then
  echo "certificates already present"
  exit 0
fi
command -v openssl >/dev/null || apk add --no-cache openssl >/dev/null

openssl req -x509 -newkey rsa:2048 -nodes -days 825 \
  -keyout ca.key -out ca.crt -subj "/O=BIMBA POC/CN=BIMBA POC Root CA"

openssl req -newkey rsa:2048 -nodes -keyout cloud-mqtt.key -out cloud-mqtt.csr -subj "/O=BIMBA POC/CN=cloud-mqtt"
printf "subjectAltName=DNS:cloud-mqtt,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n" > server.ext
openssl x509 -req -in cloud-mqtt.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 \
  -out cloud-mqtt.crt -extfile server.ext

openssl req -newkey rsa:2048 -nodes -keyout edge-bridge.key -out edge-bridge.csr -subj "/O=BIMBA POC/CN=plant01-edge"
printf "extendedKeyUsage=clientAuth\n" > client.ext
openssl x509 -req -in edge-bridge.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 \
  -out edge-bridge.crt -extfile client.ext

rm -f ./*.csr ./*.ext ./*.srl
chown 1883:1883 ./*
chmod 0644 ./*.crt
chmod 0600 ./*.key
echo "certificates generated"
