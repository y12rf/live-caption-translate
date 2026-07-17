# Security

## API keys

- ASR / LLM API keys are stored in **app DataStore (plaintext)**, not encrypted at rest.
- Keys are **never** hardcoded in the repository. Configure them only on device via Settings.
- Android backup is **disabled** (`allowBackup=false`) so keys are less likely to leave the device via cloud backup.
- Do not commit `local.properties`, keystores, or personal `.env` files.

## Network

- Cleartext HTTP is allowed so local / LAN gateways work. Prefer HTTPS in production.
- Treat any gateway URL and key as secrets; rotate keys if they leak.

## Reporting issues

Open a GitHub issue for vulnerability reports. Avoid pasting real API keys into issues or logs.
