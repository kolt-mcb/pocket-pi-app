# Plan: deliver files/images to the phone from ANY agent (host or peer)

## Problem (root cause, verified)

`piRemote.sendFile` (extension.ts ~320) broadcasts the `file` message via
`hostBcastClients`, which only reaches **direct client connections**. A **peer**
pi process (e.g. the gobm agent) has none — it connects *to* the host — so:

- The file is sent to nobody.
- The `send_file_to_phone` tool's guard `if (clientConns.size === 0)` (≈2337) is
  always true on a peer → it reports **"No phone is connected"** even when a phone
  is connected to the host.

The host already forwards arbitrary `peer_event` payloads to all clients (the
`peer_event` handler special-cases `mirror_frame`, then `hostBcastClients({...payload,
agentId})` for everything else), and there's an `emitAgentEvent` helper that does
the host/peer routing hop — but `sendFile` doesn't use it. So the fix is mostly
peer-side routing + correct gating, plus a small app-side touch.

## Goal

Any agent — the host or any peer — can deliver a file/image to the phone,
regardless of which agent's tab the phone is currently viewing, with accurate
"delivered / no phone" feedback to the calling agent.

## Two delivery paths (keep both, clarify when each applies)

1. **Push** — `send_file_to_phone` → `file` message → `FileDownloadDialog`
   (save/share). Deliberate "here's a file"; goes to *all* connected phones. This
   is the path that's broken for peers — Phase 1/2 below.
2. **Display** — the agent renders an image in its terminal (kitty graphics) → it
   appears inline in the **mirror** when the phone is viewing that agent's tab.
   Already works for peers (the forced-kitty + route_mirror images flag). Phase 4
   just documents/validates it.

---

## Phase 1: Route peer file pushes through the host (extension)

- In `sendFile`, replace the direct `hostBcastClients(...)` with peer-aware
  emission:
  - **host mode:** `hostBcastClients({ type:"file", ..., agentId: SELF_AGENT_ID })`.
  - **peer mode:** `peerSock.send({ type:"peer_event", agentId: SELF_AGENT_ID,
    payload:{ type:"file", ... } })` (mirror `emitAgentEvent`; or just call a
    shared helper).
- Host `peer_event` handler: confirm `file` payloads fall through to the general
  `hostBcastClients({...payload, agentId: sourceAgentId})` branch (they do today),
  so a peer's file reaches every connected phone, stamped with the peer's id.
- Result: gobm (peer) → host → all phones get the file.

## Phase 2: Correct the "no phone connected" feedback (extension)

A peer can't see `clientConns` (always 0), so the tool's guard is wrong there.
Give peers the host's client count:

- Host tracks `clientConns.size` (it already does) and **notifies peers** when it
  changes: on client connect/disconnect, send each peer `{ type:"host_clients",
  count }` (and include the current count in `peer_ack` so a freshly-joined peer
  knows immediately).
- Peer caches `hostClientCount`; the peer-mode branch of the `send_file_to_phone`
  tool checks that instead of `clientConns.size`.
- Tool messaging: host mode → "Sent to N device(s)"; peer mode → "Sent to the host
  for delivery to N phone(s)" (or "No phone connected" when the cached count is 0).

## Phase 3: App — show the file's source + keep the existing hardening

- The app `file` handler (PiWebSocket ~593) currently ignores `agentId`. Parse it
  and pass a source label to `FileDownload` so `FileDownloadDialog` can show e.g.
  "File from Pi-gobm" — otherwise a peer-pushed file looks like it came from the
  host.
- Keep the existing safeguards already in place (8 MiB cap, off-main-thread
  decode/write to `cache/shared/`, APK-name rejection).
- No change needed to the actual download/share flow.

## Phase 4: Images via the mirror (peers) — document + validate

- Already implemented: a peer that *displays* an image renders it in the mirror
  when the phone views that peer's tab (forced-kitty + `route_mirror` images flag,
  validated on-device for the host path).
- Validate the peer path specifically: have a peer display a plot while the phone
  is on that peer's tab → image renders inline.
- Guidance for agents: use `send_file_to_phone` to hand over a file (download,
  any tab); display in-terminal to show an image inline in the mirror (that tab).

## Phase 5: Tests & validation

- **App unit test:** `file` message with an `agentId` parses into `FileDownload`
  with the source label.
- **End-to-end (fake host, as used for the kitty validation):** simulate a peer by
  sending a `peer_event{payload:{type:"file",...}}` and confirm the host-forward
  shape `{type:"file", agentId, data,...}` drives the `FileDownloadDialog` on the
  phone (screenshot).
- **Real:** gobm (peer) runs `send_file_to_phone` while a phone is connected to the
  host → save/share dialog appears, tagged "from gobm".

## Risks / notes

- **Fan-out:** a file pushed by a peer goes to *all* phones (it's a deliberate
  push). That's the intended behavior; just be aware it's not scoped to the
  viewing tab.
- **Size:** 20 MiB cap host-side, 8 MiB base64 cap app-side — a peer→host→phone
  hop doubles the in-memory copies briefly; fine at these caps.
- **Backward compat:** old host ignores a peer's `host_clients`/`peer_event file`
  gracefully (unknown types dropped); old app shows the file without the source
  label. Gate nothing new behind a capability — the `file` message shape is
  unchanged except the added `agentId`.

## Recommended order

Phase 1 (routing — the actual fix) → Phase 2 (feedback) → Phase 3 (app source
label) → Phase 5 (validate). Phase 4 is mostly documentation of existing behavior.
