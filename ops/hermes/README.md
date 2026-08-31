# Argus Hermes bridge setup

This guide installs the optional Argus bridge on a Linux host with a systemd user session and
publishes it privately through Tailscale Serve. Argus also works without this bridge by selecting a
direct LLM provider in the app.

## 1. Install and configure Hermes Agent

Install Hermes as the same unprivileged user that will run the bridge. The bridge expects the
managed installer layout under `~/.hermes/`.

```bash
curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash
source ~/.bashrc
hermes model
hermes doctor
hermes chat -q "Reply with exactly: OK"
```

Choose the provider and model you intend to use when `hermes model` asks. See the
[official Hermes quickstart](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/getting-started/quickstart.md)
if provider credentials are not configured yet.

## 2. Copy and test the bridge

From an Argus checkout on the server:

```bash
python3 -m unittest discover -s ops/hermes -p 'test_bridge.py'

install -d -m 700 "$HOME/argus-bridge" "$HOME/.config/systemd/user"
install -m 600 ops/hermes/bridge.py "$HOME/argus-bridge/bridge.py"
install -m 600 ops/hermes/state_query_contract_v2.json \
  "$HOME/argus-bridge/state_query_contract_v2.json"
install -m 600 ops/hermes/argus-bridge.env.example \
  "$HOME/argus-bridge/argus-bridge.env"
install -m 644 ops/hermes/argus-bridge.service \
  "$HOME/.config/systemd/user/argus-bridge.service"
```

Generate the bearer token without printing it, then replace the placeholder in the private env
file:

```bash
ARGUS_RANDOM_TOKEN="$(openssl rand -hex 32)"
sed -i "s/replace-with-a-random-secret/$ARGUS_RANDOM_TOKEN/" \
  "$HOME/argus-bridge/argus-bridge.env"
unset ARGUS_RANDOM_TOKEN
chmod 600 "$HOME/argus-bridge/argus-bridge.env"
```

Edit `ARGUS_MODEL` if its value differs from the model selected in Hermes. Keep
`ARGUS_BRIDGE_BIND=127.0.0.1`: the bridge intentionally refuses non-loopback binds.

## 3. Start the user service

```bash
systemctl --user daemon-reload
systemctl --user enable --now argus-bridge.service
systemctl --user --no-pager --full status argus-bridge.service
```

For a dedicated headless account, an administrator can keep the user manager alive after logout:

```bash
sudo loginctl enable-linger "$USER"
```

Check the authenticated health endpoint locally. Sourcing this file does not print the token; do
not run these commands with shell tracing enabled.

```bash
set -a
. "$HOME/argus-bridge/argus-bridge.env"
set +a
curl --fail --silent --show-error \
  -H "Authorization: Bearer $ARGUS_BRIDGE_TOKEN" \
  "http://127.0.0.1:${ARGUS_BRIDGE_PORT}/health/v2"
unset ARGUS_BRIDGE_TOKEN
```

The JSON response must report `status: "ok"`, compile versions containing `2`, and act versions
containing `1`, `2`, and `3`.

## 4. Publish it privately with Tailscale Serve

Install Tailscale on both the server and Android device and sign them into the same tailnet. Then
publish only the loopback service:

```bash
tailscale serve --bg localhost:8092
tailscale serve status
```

Serve prints an HTTPS URL similar to `https://your-host.your-tailnet.ts.net`. It is private to the
tailnet and supplies the TLS certificate Argus expects. Do **not** use Tailscale Funnel: Funnel makes
the endpoint public on the internet.

## 5. Configure Argus

On the Android device:

1. Connect Tailscale to the same tailnet.
2. Open **Settings → LLM provider** in Argus and select **Hermes**.
3. Enter the HTTPS Serve URL with no endpoint path.
4. Enter the bearer token from `~/argus-bridge/argus-bridge.env`.
5. Save and run **Check connection**.

Argus calls `/health/v2` before compile or generative requests and reports an incompatible stale
bridge instead of silently falling back.

## Upgrade

After pulling a newer Argus checkout, test and copy the three bridge files atomically, then restart:

```bash
python3 -m unittest discover -s ops/hermes -p 'test_bridge.py'
install -m 600 ops/hermes/bridge.py "$HOME/argus-bridge/bridge.py"
install -m 600 ops/hermes/state_query_contract_v2.json \
  "$HOME/argus-bridge/state_query_contract_v2.json"
systemctl --user restart argus-bridge.service
systemctl --user --no-pager --full status argus-bridge.service
```

Repeat the authenticated `/health/v2` check. Its `source_sha256` should match the normalized source
hash of the deployed `bridge.py`.

## Troubleshooting

- `runtime Hermes non trovato`: install Hermes with the official managed installer as the service
  user; verify `~/.hermes/hermes-agent/venv/bin/python` and run `hermes doctor`.
- `401 unauthorized`: the token saved in Argus and `ARGUS_BRIDGE_TOKEN` do not match.
- Connection failure from Android: confirm Tailscale is connected, use the HTTPS Serve URL (not
  `127.0.0.1:8092`), and inspect `tailscale serve status`.
- `bridge_incompatible`: deploy the bridge files from the same Argus version as the app and verify
  `/health/v2`.
- Service crash or model timeout: inspect
  `journalctl --user -u argus-bridge.service -n 100 --no-pager` and test Hermes directly with
  `hermes chat -q "Reply with exactly: OK"`.

For the complete request, response, validation, and rollout contract, see
[`docs/design/hermes-bridge-contract.md`](../../docs/design/hermes-bridge-contract.md).
