---
name: safe-cross-kr-builder
description: Build or review the Safe Cross KR Android pedestrian-navigation system for blind and low-vision users, especially public crossing data, accessible guidance, crosswalk-aware on-device pedestrian-signal estimation, optional authorized live-signal fusion, and fail-safe release evidence. Use only inside this project; do not use for generic navigation apps.
---

# Safe Cross KR Builder

Build this project as a safety-assistance system, not an autonomous crossing authority.

## Read the project source of truth

Before changing a subsystem, read the matching documents:

- Product scope and prohibited claims: [PRD.md](PRD.md)
- Testable requirements and safety scenarios: [SRD.md](SRD.md)
- Architecture, data, mobile, ML, and operations: [TRD.md](TRD.md)
- Official source URLs and ingestion rules: [DATA_SOURCES.md](DATA_SOURCES.md)
- Delivery sequence and release gates: [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)
- Copy-ready implementation tasks: [VIBE_CODING_PROMPTS.md](VIBE_CODING_PROMPTS.md)

Treat requirement IDs in PRD/SRD as acceptance criteria. Report which IDs a change satisfies.

## Preserve the safety boundary

- Public traffic-light and crosswalk datasets describe location and facility attributes. Do not infer the live signal state from static fields or nominal timing.
- Treat authorized real-time signal feeds as a separate dynamic source. Verify provider, intersection, movement, source time, freshness, and quality; never invent an endpoint or contract.
- A model observation is not a user decision. Route observations through the versioned crossing state machine.
- Never expose `GREEN_CANDIDATE` to users.
- Require field-verified crossing identity, crosswalk scene or verified geometry, fresh accurate location, crossing bearing, a unique pedestrian-signal target, same-track temporal agreement, acceptable frame quality, approved ODD, and an enabled model before `GREEN_ESTIMATE`.
- Use crosswalk perception to validate scene direction and signal relevance, not as evidence that the signal is green.
- Keep map, crosswalk, camera, temporal, and optional official observations independent. A safety-critical conflict is a veto and must return `UNKNOWN`; do not average it away.
- If any required input is missing, stale, conflicting, out of range, or disabled, return `UNKNOWN`.
- Optimize against false green before missed green. Do not lower thresholds to improve a headline accuracy metric.
- Do not claim “safe,” “100%,” “no vehicles,” or instruct the user to cross. Use the reviewed Korean messages in PRD.
- Do not add vehicle-safety claims unless a separately scoped, validated system and updated requirements authorize them.

## Keep camera data on device

Production camera frames may pass only from CameraX memory buffers to the on-device estimator.

- Do not serialize, persist, log, attach to crash reports, send to analytics, or transmit frames.
- Close `ImageProxy` in a `finally` block and unbind the camera when the session loses visibility or ends.
- Keep the frame type out of remote DTOs and generic logging interfaces.
- Model-improvement capture requires a separate research flow, explicit consent, retention rules, and de-identification; ordinary feature work does not authorize it.

## Handle location and secrets minimally

- Use precise location only during an explicit navigation session.
- Keep raw coordinates and destinations out of normal logs, traces, metrics, crash reports, and test output.
- Keep provider keys on the backend. Never embed them in the APK, repository, fixture, or prompt.
- Distinguish missing (`null`) from explicitly absent (`false`) facility attributes.
- Retain source, reference date, ingest date, verification state, and history for every published facility.

## Build accessible-first

Every P0 flow must work without seeing the screen, while offering rich high-contrast cues for low-vision users.

- Give actions unique, purpose-oriented semantics, roles, states, and predictable focus order.
- Use at least 48dp touch targets and at least 64dp for primary safety controls.
- Do not communicate state by color alone.
- For low-vision users, provide high-contrast UI (black background #000000, 4dp high-contrast yellow borders #FFD600, 80dp+ large directional arrows, 36sp+ extra-bold distance text).
- Display real-time GPS signal strength (%) and accuracy radius (±m) badge on top of navigation screens to inform users of signal quality.
- Route speech through the priority arbiter: safety, crossing, route, then information.
- Structure walking navigation into the 4-stage state machine (`WalkingMode`: IDLE, WALKING, APPROACHING_CROSSING, CROSSING) with stage-specific safety guidance.
- Format pedestrian navigation instructions through `BlindGuidanceFormatter` (1~12 clock-face directions, step count at 0.65m per step, stripping visual landmarks like "OO방면으로", and body turn angle guidance).
- Continuously track real-time geomagnetic compass heading via `Sensor.TYPE_ROTATION_VECTOR` in `DevicePoseTracker`.
- Deliver `ORIENTATION_ALIGNED` haptic compass feedback (60ms-60ms-60ms double pulse) and speech when the user aligns their body within 18° of the route path.
- Automatically trigger camera crossing assist (`TriggerCrossingAssist`) when approaching crosswalks (15m/8m) to avoid requiring white-cane users to touch the screen while walking.
- Emit immediate speech guidance upon maneuver turn/step transitions (`QUEUE_FLUSH`) and deliver 30m / 15m approach cues.
- Integrate SK TMAP national POI search API and Geocoder fallback to search any national station, building, or address, displaying distance from GPS and 5km walking threshold badges.
- Render national standard Ministry of Land, Infrastructure and Transport VWorld 2D precision electronic maps (1:1000 detailed building/alleyway in Korean) with 3-tier fallback (OSM, CartoDB) and live user radar pulse markers (🔵) / off-route feedback (🔴).
- Standardize TMAP pedestrian pathway mapping (`facilityType 11` = flat pathway) to completely prevent overhead bridge misdirection.
- Maintain background tracking and TTS audio through `NavigationForegroundService` with notification controls.
- Provide repeat and immediate stop actions; avoid drag-only interactions.
- Run automated Compose accessibility checks and a manual TalkBack flow for relevant UI changes.

## Follow the subsystem workflow

### Public data

1. Preserve the downloaded source and SHA-256.
2. Parse into staging with explicit column aliases and strict tri-state booleans.
3. Quarantine invalid coordinates, dates, keys, and unknown enum values.
4. Preserve administrative source names and apply aliases separately.
5. Generate many-to-many spatial match candidates; do not merge different crossing directions by distance alone.
6. Publish immutable versions only after review, keeping raw records and field verification separate.

### Android camera, vision AI, and decision engine

1. Integrate fake crosswalk, signal, and association estimators before real models.
2. Use CameraX latest-frame analysis and verify lifecycle cleanup.
3. Implement high-speed RGB-to-HSV color space segmentation in `CameraVisionSignalEstimator` to separate illumination (V) from hue (H) and saturation (S), adapting to sunlight backlight/overexposure and low-light shade.
4. Tune color spectrum precisely to Korean pedestrian signal cyan-green (Hue 145°~195°) and pure-red (Hue 0°~15°, 345°~360°) LED wavelengths; restrict ROI height (8%~65%) to exclude high overhead road signals.
5. Filter out horizontal vehicle traffic lights (aspect ratio W/H > 1.35) and yellow/orange lights (Hue 25°~55°).
6. Verify vertical 2-light pedestrian signal geometry (top red / bottom green) and enforce Red Precedence (Zero False-Green) when ambiguous.
7. Wrap `org.tensorflow.lite.Interpreter` with `TfliteModelRunner` for on-device hardware acceleration (NPU/CPU multithreading) with graceful fallback.
8. Apply `LocalVlmSignalVerifier` 5-frame temporal rolling buffer to suppress single-frame flicker and enforce ≥60% frame stability for green states.
9. Validate signed model metadata, labels, tensor contract, and SHA-256 before loading.
10. Establish a CPU baseline, then add GPU/NPU with tested fallback.
11. Restore crosswalk masks and signal boxes into the same source coordinate system.
12. Select a target signal from field-verified map links, crosswalk direction, device pose, and signal geometry before classifying a user-visible state.
13. Track the same ephemeral signal target across frames; never treat a transition between different boxes as RED-to-GREEN.
14. Keep the decision engine pure and deterministic, with monotonic timestamps.
15. Cover SRD scenarios ST-001 through ST-015 before enabling user-facing estimates.

### Authorized live signal

- Implement a provider-neutral `SignalStatusProvider` and a deterministic fake before a network adapter.
- Build a real adapter only from an official approved contract and credentials; lack of access is a product dependency, not permission to scrape or guess.
- Map provider intersection and movement identifiers only through field-verified directional links.
- Reject stale, out-of-order, unmapped, malformed, maintenance, and clock-skewed observations.
- If official and camera observations materially conflict, return `UNKNOWN` with a stable reason code.
- Keep official-only user-facing green disabled until separately approved by the safety owner.

### External routing (TMAP Pedestrian API)

- Verify the current official API contract and terms before implementation.
- Call TMAP pedestrian API with `searchOption="30"` (stairs exclusion).
- Enforce mandatory accessibility limitation banner (`DisclaimerBanner`) stating that TMAP route does not guarantee curb ramps (<=2cm), tactile paving, or acoustic signals.
- Read disclaimer text aloud via TTS upon entering route summary, require explicit user confirmation before starting navigation.
- Put providers behind a `PedestrianRouter` adapter and keep a fake implementation for tests.
- Map provider errors to stable internal codes and prevent retry storms.
- Do not describe a standard pedestrian route as a blind-safe route.

## Verify each change

Run the smallest relevant set plus adjacent safety regression tests.

For code changes, verify:

- formatting and lint
- unit and contract tests
- relevant PostGIS/ETL or Android instrumentation tests
- safety state-machine scenarios when guidance inputs change
- crosswalk-to-signal association, same-track continuity, and official-camera conflict scenarios
- TalkBack checks when UI or speech changes
- automated security scans: secret scanner (`secret_scanner.py`), prohibited phrase scanner (`safety_phrase_scanner.py`), and SBOM generator (`sbom_generator.py`)
- release rehearsal drill (`rehearsal_drill.py`) and Go/No-Go report before candidate release
- no frame/coordinate leakage when camera, networking, logging, or observability changes

Do not delete a failing safety test or weaken a requirement to make a build pass. Stop and report a blocker when the required fix needs new legal authority, production credentials, external coordination, or a product decision that materially changes risk.

## Prepare release evidence

Do not activate a production or user-facing green estimate merely because tests pass. Prepare versioned evidence for independent approval:

- app, backend, data, model, ODD, and state-machine versions
- per-slice false-green events and confidence bounds
- ST-001 through ST-015 results
- signal-only, crosswalk-context, map/heading, and authorized-live-source ablation results
- official-source freshness, movement mapping, outage, and camera-conflict results
- device latency, memory, thermal, and fallback results
- TalkBack manual test record
- frame and location privacy checks
- model, region, and global kill-switch drills
- known limitations and explicit no-go items

When evidence is incomplete, label the release `NO-GO` or keep the feature in shadow mode.
