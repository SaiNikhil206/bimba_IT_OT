---
title: BIMBA Setu
emoji: 🌉
colorFrom: indigo
colorTo: blue
sdk: docker
app_port: 8090
pinned: false
---

BIMBA Setu: SCADA edge server and console. The image is built from
https://github.com/SaiNikhil206/bimba_IT_OT (branch release/dev, folder bimba-setu).

Set these under Settings > Variables and secrets: BIMBA_DB_HOST, BIMBA_DB_PORT, BIMBA_DB_NAME,
BIMBA_DB_USER, BIMBA_DB_PASSWORD (secret), BIMBA_DB_SCHEMA, BIMBA_SECRET_KEY (secret),
BIMBA_PUBLIC_URL, BOOMI_ES_PRODUCE_TOKEN (secret).
