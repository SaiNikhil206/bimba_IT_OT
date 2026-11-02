# BIMBA Setu on Oracle Cloud (Always Free)

Setu and Caddy (automatic HTTPS) on one Ampere ARM VM. Free forever within the Always Free limits; a card is
only used to verify your identity.

| Port | Who connects | What |
|---|---|---|
| 443 (and 80) | People, in a browser | The Setu console, through Caddy with a Let's Encrypt certificate |
| 8443 | Site agents | Setu directly, its own certificate and mutual TLS (client certificates) |

## 1. Oracle account and VM
1. Sign up at https://www.oracle.com/cloud/free/ . Choose your home region carefully; it cannot be changed.
   Stay on the free account (do not upgrade to Pay As You Go).
2. **Compute > Instances > Create instance**
   - Image: **Canonical Ubuntu 22.04** (aarch64) or 24.04
   - Shape: **Ampere (VM.Standard.A1.Flex)**, for example **2 OCPU / 12 GB** (the free limit is 4 OCPU / 24 GB in total)
   - Networking: keep the default VCN, **assign a public IPv4 address**
   - SSH keys: **Save private key** (a `.key` file). Keep it safe.
   - If it says "Out of capacity", try again later or pick another availability domain.
3. Copy the instance's **public IP**.

## 2. Open the ports in Oracle's network
**Networking > Virtual cloud networks > your VCN > Security Lists > Default > Add ingress rules**
(Source `0.0.0.0/0`, protocol TCP, one rule each): destination ports **80**, **443**, **8443**.

## 3. Prepare the VM
```bash
ssh -i <your-key-file>.key ubuntu@<PUBLIC_IP>

# Oracle's Ubuntu images also block ports inside the VM: open them
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80   -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443  -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 8443 -j ACCEPT
sudo apt-get update && sudo apt-get install -y iptables-persistent && sudo netfilter-persistent save

# Docker
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu && newgrp docker
```

## 4. Get the code and configure
```bash
git clone --branch release/dev https://github.com/SaiNikhil206/bimba_IT_OT.git
cd bimba_IT_OT/deploy/oracle
cp .env.example .env
nano .env        # PUBLIC_IP, DOMAIN, the BIMBA_DB_* values, BIMBA_SECRET_KEY (openssl rand -hex 32)
```
No domain? Use `DOMAIN=<PUBLIC_IP>.sslip.io` (for example `129.146.10.20.sslip.io`): it resolves to the IP and
Let's Encrypt will issue a certificate for it.

## 5. Start
```bash
docker compose up -d --build        # the first build takes about 5-10 minutes
docker compose logs -f setu         # wait for "Started BimbaSetuApplication"
```

## 6. Check
- Console: `https://<DOMAIN>` (sign in with your Setu admin account)
- Status: `https://<DOMAIN>/system/bimba-status` should show `siteAgents.lastOk` filled and `configError: null`

## 7. Connect a site agent (real mutual TLS)
In the console: **Site agents > Add a computer**, set the Setu address to `https://<DOMAIN>:8443`. The generated
command carries the certificate fingerprint, so the agent trusts Setu's own certificate:
```bash
sudo bimba-site-agent install --server https://<DOMAIN>:8443 --token <TOKEN> --site "<name>" --trust-cert "<FINGERPRINT>"
```
The data volume keeps the agent certificate authority, so agents stay enrolled across restarts and updates.

## Operate
```bash
git pull && docker compose up -d --build     # update to the latest code
docker compose restart setu                   # restart
docker compose logs --tail 200 setu           # logs
docker volume ls                              # setu-data holds the keys: back it up
```
If the database host only allows listed IP addresses, add the VM's public IP to its allow list.
