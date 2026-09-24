# 0003 — Reach home Ollama via two Telegram bots

## Context

Home mode needs the AWS backend to reach an Ollama server running on a home
network with no public IP. The usual options are a VPN (Tailscale/WireGuard),
a tunnel service (ngrok/Cloudflare Tunnel), or port forwarding — each adds a
box or a subscription to maintain, or opens an inbound port at the house.

## Decision

Use two Telegram bots and Telegram's direct bot-to-bot messaging (an
official feature since May 2026): a **Cloud Relay Bot** running as an AWS
Lambda webhook, and a **Home Bridge Bot** running as a small process at
home that long-polls outbound. Both enable Bot-to-Bot Communication Mode in
BotFather so they can DM each other directly. AWS-side requests go
Core → Cloud Relay Bot → (bot-to-bot DM) → Home Bridge Bot → local Ollama,
and back the same way.

## Consequences

- Zero inbound exposure at the house, ever — the home process only makes
  outbound connections, same as any home bot always could.
- No VPN box, tunnel subscription, or port-forwarding rule to maintain.
- Adds a dependency on Telegram as the transport, and on its ~4,096
  character message-size limit, which shapes how prompts are chunked
  (see `architecture.md` §04).
- The round trip is materially slower than a direct call and can't be
  synchronous — this is what led to the async request/push design in
  `architecture.md` §04, not a plain request/response API.
