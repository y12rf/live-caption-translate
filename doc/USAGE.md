# Usage & setup

Install overview is in the [README](../README.md). This page covers configuration details.

## Configure APIs

Keys stay **on device** only. Open **Settings** and set at least:

| Setting | What to put |
|---------|-------------|
| ASR Base URL / API Key / Model | Speech-to-text provider |
| ASR API style | OpenAI transcriptions, or chat+audio for some gateways |
| ASR auth | `Bearer` or `Api-Key` |
| LLM Base URL / API Key / Model | Chat-completions compatible translator |
| LLM auth | Usually `Bearer` |
| Input / output language | ASR language and translation target (UI language is separate) |

### Tips

- **Full URL**: use the URL as-is (no `/v1/...` append) for custom gateways.
- **Prompts**: placeholders `{{from}}`, `{{to}}`, `{{text}}`, `{{history}}`, `{{glossary}}`.
- **Glossary**: term pairs inject into `{{glossary}}`.
- **VAD**: defaults are fine for most lectures; tune silence / max utterance if needed.
- **ASR-only**: skip translation when you only need transcripts.
- **Overlay**: size, colors, opacity, text mode; lock/unlock from the recording notification.

## Permissions

| Permission | Used for |
|------------|----------|
| Microphone | Live capture |
| Notifications | Recording status (Android 13+) |
| Display over other apps | Floating captions |
| Media projection | Internal audio |

## Everyday use

| Action | How |
|--------|-----|
| Start / pause / stop | Live controls; stop saves history + WAV |
| Floating subtitles | Toggle on Live; lock from notification |
| Fullscreen bilingual | Top-bar fullscreen icon |
| Replay / reprocess / export | History |

## Known limitations

- Latency = VAD cut length + ASR/LLM network.
- Internal audio quality varies by OEM.
- API keys stored in plain text on device; cleartext HTTP allowed for LAN gateways.
- Room schema jumps may wipe history.
- FFmpeg native libs increase APK size.

See also: [BUILD.md](BUILD.md) · [ARCHITECTURE.md](ARCHITECTURE.md) · [SECURITY.md](../SECURITY.md)
