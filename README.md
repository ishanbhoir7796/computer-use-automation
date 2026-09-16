# Computer-Use Automation System

A system that lets an LLM ("computer use") discover how to accomplish a goal
against a legacy web app once, then replays that discovery **deterministically**
- no LLM involved - as a reusable, typed capability an AI agent can invoke in
production.

> Built for the interface.ai take-home assignment. See [`/REPORT.md`](./REPORT.md)
> for the full design write-up: architecture, artifact schema, determinism &
> error handling, multi-tenant story, escalation model, safety, and cuts.

---

## What's in this repo

Two independent projects, each with its own build:

| Project | What it is |
|---|---|
| `mock-bank-app/` | A deliberately "legacy" Spring Boot banking UI - the target being automated. Stand-in for a real bank/credit union back-office app: table-based layout, no test IDs, server-rendered. |
| `cua-core/` | The automation system itself: LLM-driven discovery agent, the typed `Capability` artifact schema, a deterministic replay engine, safety guardrails, and human escalation/handoff. |

They talk to each other over plain HTTP - `cua-core` has no compile-time
dependency on `mock-bank-app` at all, the same way real automation has no
special access to the legacy app it's driving.

---

## Prerequisites

- Java 21
- An Anthropic API key - **only needed to run a *new* discovery session.**
  Replaying an already-recorded capability needs no API key at all (see
  "Running without live services" below).

---

## Setup

### 1. Start the target app

```bash
cd mock-bank-app
./gradlew bootRun
```

Runs on `http://localhost:8080`. Leave this running in its own terminal for
everything below.

### 2. Set your Anthropic API key (only if running discovery)

Set it as an environment variable - never commit it to a file:

```bash
# macOS/Linux
export ANTHROPIC_API_KEY=sk-ant-...

# Windows (PowerShell)
$env:ANTHROPIC_API_KEY="sk-ant-..."
```

---

## Demo path

### Run a real discovery session

This drives the live app with an actual LLM-in-the-loop, and - on success -
saves a reusable `Capability` artifact plus a full transcript to an evidence
directory. Sensitive-looking text (account numbers, dollar amounts, tokens)
is scrubbed from the saved transcript before it's written to disk.

```bash
cd cua-core
./gradlew discover --args='--goal="search for member 10001 and read their savings balance" --base-url=http://localhost:8080 --entry-route=/search --capability-id=lookup_savings_balance --evidence-dir=evidence/discovery_run --input=member_id=10001'
```

On success, this writes:
- `evidence/discovery_run/discovery_transcript.json` - the raw, turn-by-turn record of what the model saw and did, and why
- `evidence/discovery_run/lookup_savings_balance.json` - the resulting `Capability` artifact
- `evidence/discovery_run/screenshots/` - one screenshot per turn

If the model gets stuck and can't safely continue, this run automatically
pauses and waits for a human operator (see "Human escalation demo" below).

### Replay the resulting artifact - deterministically, no LLM

```bash
./gradlew replay --args='--capability-file=evidence/discovery_run/lookup_savings_balance.json --base-url=http://localhost:8080 --evidence-dir=evidence/replay_run --input=member_id=10001'
```

**The same artifact generalizes to a different input**, with zero re-recording:

```bash
./gradlew replay --args='--capability-file=evidence/discovery_run/lookup_savings_balance.json --base-url=http://localhost:8080 --evidence-dir=evidence/replay_run_2 --input=member_id=10003'
```

### Replay against a business outcome (a nonexistent member)

```bash
./gradlew replay --args='--capability-file=evidence/discovery_run/lookup_savings_balance.json --base-url=http://localhost:8080 --evidence-dir=evidence/replay_run_notfound --input=member_id=99999'
```

Expect `status: BUSINESS_OUTCOME`, `outcomeCode: MEMBER_NOT_FOUND` - not a
hard failure.

---

## Running without live services

Everything **except discovery** requires no API key and no AI call at all:
`./gradlew replay` runs purely against the saved `Capability` JSON and the
live target app. This is the deliberate production path - replay is how an
AI agent actually invokes a capability, and it never touches an LLM.

Pre-recorded evidence from a real discovery run (transcript, artifact, and
screenshots) is committed in [`/evidence/`](./evidence/), so the full pipeline
can be inspected without needing to run discovery again or hold an API key.

---

## Human escalation demo

Both `discover` and `replay` are wired to pause and hand off to a human
whenever they hit something they can't safely resolve alone: during
discovery, the model saying it is stuck; during replay, a known failure
state (like a session timeout) that is marked as needing a human, or a
risky step in a capability that has not been reviewed yet.

When that happens, the run writes an `InterventionRequest` to a shared
`control_state.json` file inside its evidence directory, and exposes the
*live* browser session over Chrome DevTools Protocol so a human operator
can attach to that exact same session, not a fresh one. The run then waits
until the operator resolves it.

In a second terminal, while a run is paused:

```bash
cd cua-core
./gradlew operatorConsole --args='evidence/<run>/control_state.json'
```

The console shows the intervention context and accepts commands:

```
click <role> <name>
fill <role> <name> = <value>
resume
abort
```

---

## Project structure (cua-core)

```
com.interfaceai.cuacore/
  schema/        Capability, Locator, CapabilityStep, OutcomeSpec, ReplayResult, ...
  surface/       Surface interface + PlaywrightSurface (accessibility-based perception/action)
  guardrails/    AllowlistConfig, RiskPolicy, Redactor
  agent/         DiscoveryAgent (Claude-driven loop), ClaudeClient, Recorder, KnownOutcomes
  replay/        ReplayExecutor, OutcomeClassifier
  escalation/    InterventionRequest, ControlState, ControlStateStore, HandoffController
  console/       OperatorConsole (the human-operator CLI)
  cli/           DiscoverCommand, ReplayCommand (entrypoints)
```

## Configuration

`cua-core/src/main/resources/application.yaml`:

```yaml
anthropic:
  api-key: ${ANTHROPIC_API_KEY:PLACEHOLDER_REPLACE_ME}
  model: claude-sonnet-4-5-20250929
```
