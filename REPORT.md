# Design Report: Computer-Use Automation System

## 1. Architecture

The system is two independent Java/Spring Boot projects in one repo, talking
over plain HTTP, with no compile-time dependency between them:

- `mock-bank-app` - a deliberately "legacy" banking UI (server-rendered
  Thymeleaf templates, table-based layout, no test IDs) standing in for a
  real bank/credit union back-office app.
- `cua-core` - the actual automation system, built as a set of packages that
  each map to one core requirement from the brief:

```
schema/       the Capability artifact contract (3.2)
surface/      perceive/act abstraction + Playwright implementation (3.1)
guardrails/   allowlist and risk policy (3.4)
agent/        the LLM-driven discovery loop and recorder (3.1, 3.2)
replay/       deterministic execution engine (3.3)
escalation/   human handoff mechanism (3.6)
cli/          runnable entrypoints tying it together
```

`cua-core` is a plain CLI tool (`spring.main.web-application-type: none`),
not a running service. The brief is explicit that scaling infrastructure
(queues, clusters, a REST API) is not rewarded here, so the architecture
stays to the minimum needed to demonstrate the full loop: a goal in, an
artifact out, a replay of that artifact, evidence for both.

Key trade-off: Java/Spring Boot was used rather than Python. This meant more
boilerplate for the artifact schema (Java has no equivalent to Pydantic's
concise validated models) and no official Anthropic SDK, so the discovery
loop talks to the Anthropic Messages API directly over `java.net.http`.
In exchange, the project demonstrates a stack closer to typical enterprise
Java shops, which is a reasonable fit for a company automating regulated
back-office software.

## 2. Artifact schema

The `Capability` artifact is deliberately decoupled from the raw model
transcript (`DiscoveryTranscript`), which is a separate, messier,
model-facing record kept only for evidence and debugging. The `Recorder`
class converts one into the other.

Shape of `Capability`:

- `target: TargetFingerprint` - identifies the logical app/vendor product
  (`appId`, `vendorProduct`, `uiTechnology`) separately from any one
  tenant's concrete URL (`baseUrlPattern` uses a `{base_url}` placeholder).
  This is the seam for multi-tenant reuse, discussed in section 4.
- `inputSchema: List<InputParamSpec>` and `outputSchema: List<OutputFieldSpec>` -
  typed, named parameters in and results out, each with a `sensitive` flag.
- `steps: List<CapabilityStep>` - each step has an `action`, an optional
  `Locator`, a `valueTemplate` (either a literal or a `{{param}}`
  reference), a `riskLevel`, and a `retry` policy.
- `successCondition: DetectionRule` - what proves the goal was reached.
- `knownOutcomes: List<OutcomeSpec>` - the declared error taxonomy (see
  section 3).
- `reviewed: boolean` - a draft/approved flag that gates unattended replay
  of risky steps (section 5).

Locator design is the most deliberate part of this schema. Every locator has
a `strategy` (`ROLE_NAME`, `LABEL_TEXT`, `TEXT_EXACT`, or `CSS`) and an
ordered list of `fallbacks`. Role and accessible name is the primary
strategy because it does not depend on CSS classes, ids, or DOM structure -
exactly the properties legacy apps do not reliably offer, but that every
interactive control still has in some form. `CSS` is present only as a
last-resort fallback, used concretely for table cells (below).

One real problem surfaced during actual discovery runs, worth describing
because it shaped the final design: the accessibility snapshot only exposes
*interactive* elements (inputs, buttons, links) with a role and computed
name, because those genuinely have a browser-computed accessible name.
Static table cells (e.g. a balance figure) do not. Early runs solved this by
having the model invent a name (e.g. "Savings Balance") for the LLM to read,
but a role/name locator with that name is not something a real browser can
resolve, since the browser never computed that name for that element. The
first attempt made the invented name match the literal cell value (e.g.
"$12,003.44"), which "worked" for the exact member recorded but broke the
entire point of a reusable capability - it would never match a different
member's balance. The fix was to give table cells a real, computed XPath
(row label plus column index, e.g.
`//tr[td[1][normalize-space()="Savings"]]/td[3]`) alongside an
LLM-readable invented name, and to have the recorder use the actual
resolved locator from execution (`TranscriptStep.resolvedLocator`) rather
than reconstructing one from scratch. This is captured directly in the
evidence: `evidence/discovery_run/lookup_savings_balance.json` step
`s4_extract` uses a `CSS` strategy locator built from that XPath, and
replaying it against a different member correctly extracts that member's
own balance instead.

## 3. Determinism and error handling

Replay (`ReplayExecutor`) never invokes the LLM. It walks `Capability.steps`
in order, resolves `{{param}}` templates against the caller's supplied
inputs, and executes each action through the same `Surface` interface
discovery used. After every step, `OutcomeClassifier` checks the current
page against `knownOutcomes` before continuing, not only when an exception
is thrown - this matters because conditions like "no records found" render
as a normal page, not an error.

The result contract (`ReplayResult`) has four states:

- `SUCCESS` - success condition met, declared outputs collected.
- `BUSINESS_OUTCOME` - a known, legitimate answer (e.g. `MEMBER_NOT_FOUND`).
  `isOkForCaller()` returns true for this, same as `SUCCESS` - the caller
  got a real answer, not an error.
- `HARD_FAILURE` - an unrecognized or non-escalating failure, with
  `failedStepId`, `expected`, and `observed` populated for debugging.
- `ESCALATED` - a `HARD_FAILURE` outcome flagged `escalate: true`, or a
  risky step in an unreviewed capability, routed to a human instead of
  failed outright (see section 5 for what actually happens next).

Recoverable conditions (category `RECOVERABLE`, e.g. the large-deposit
confirmation interstitial in the mock app) are handled by a declared
`recoveryAction` (`dismiss_and_continue` clicks a known button and resumes;
`wait_and_retry` backs off and re-checks) rather than by the model
improvising.

Because a single successful discovery run can only ever walk one path
through the app, it cannot discover the not-found, session-expired, or
permission-denied branches it never took. `knownOutcomes` is therefore
authored separately (`agent/KnownOutcomes.java`), standing in for what
would be a human reviewer's annotation pass in production. This is a
genuine simplification, discussed further in section 7.

A second real bug worth noting: an early discovery run picked a success
condition string that happened to render with literal tab characters from
the browser's text layout. Rather than trying to prompt the model into
avoiding this exactly, the comparison itself was made whitespace-tolerant
(`Snapshot.normalizeWhitespace`, applied consistently in `ReplayExecutor`,
`OutcomeClassifier`, and `PlaywrightSurface.assertText`), which is a more
robust fix than trying to control exactly what text a model will pick.

Evidence for this section: `evidence/replay_run/replay_result.json` (same
member as discovery, `SUCCESS`), `evidence/replay_run_2/replay_result.json`
(a different member, `SUCCESS`, proving the artifact genuinely
generalizes), and `evidence/replay_run_notfound/replay_result.json`
(`BUSINESS_OUTCOME`, `MEMBER_NOT_FOUND`).

## 4. Heterogeneity and multi-tenant

The `Surface` interface is the seam. `perceive()` returns a role/name/value
description and raw visible text; `click`/`fill`/`select`/`extract` are
addressed by `Locator`, never by pixel coordinates or a raw DOM path.
Nothing above this interface (the discovery loop, the recorder, the replay
executor) knows it is talking to Playwright. A desktop implementation of
the same interface, driving Windows UI Automation or macOS Accessibility
APIs instead, would plug in without touching the schema, the agent loop, or
the replay engine - those platforms expose the same role/name concept
natively. A legacy web app with framesets or deeply nested tables is
already handled by the same `PlaywrightSurface`, since role/name resolution
and the table-cell XPath fallback do not assume any particular markup
quality.

For multi-tenant reuse, `TargetFingerprint` deliberately separates the
logical app identity (`appId`, `vendorProduct`) from any one tenant's
concrete URL (`baseUrlPattern` is a template, resolved with a `base_url`
supplied at invocation time, not baked into the artifact). The same
artifact recorded against one tenant's instance of a vendor product can be
replayed against a different tenant's instance by supplying a different
`base_url`, as long as the underlying UI's roles/names/labels are close
enough for the primary locator strategy (and its fallbacks) to resolve.
Locator fallback chains are the mechanism that absorbs small
branding/config differences between tenants without a full re-recording.

Drift detection was not built (the brief does not expect this to be built,
only designed for). The credible design is: replay already surfaces a
structured `HARD_FAILURE` with `failedStepId`, `expected`, and `observed`
whenever a locator fails to resolve or a success condition is not met.
Aggregating that signal across replay runs of the same `capabilityId`
(a stretch goal explicitly listed in the brief as "multi-run stability")
would give a concrete, low-effort way to detect when a tenant's UI has
drifted enough that the artifact needs review, without needing to build
that aggregation now.

## 5. Escalation and handoff

Detection: automation calls `HandoffController.escalate(...)` when it hits
a state it cannot safely resolve. This is wired into both execution paths:

- During discovery, when the model calls `finish_stuck`, `DiscoveryAgent`
  escalates rather than simply ending the run. If the operator resumes, the
  agent shows the model a fresh snapshot of wherever the page is now and
  lets it continue working toward the goal; if the operator aborts, the run
  ends with `outcome: stuck` as before.
- During replay, when a step is `RISKY` in a capability that is not yet
  `reviewed`, or when a `HARD_FAILURE` outcome is flagged `escalate: true`,
  `ReplayExecutor` escalates. If the operator resumes, the executor
  re-checks the page against the success condition once and reports
  `SUCCESS` or `HARD_FAILURE` accordingly, rather than trying to resume
  stepping through the remaining steps automatically; if the operator
  aborts, it reports `ESCALATED`.

Handoff mechanism: `PlaywrightSurface.launchExposedForHandoff()` starts
Chromium with `--remote-debugging-port` open (Chrome DevTools Protocol).
Escalating writes an `InterventionRequest` (goal/capability, reason,
failed step, screenshot, page excerpt, and the CDP endpoint) into a shared
`ControlState` JSON file, flips `owner` to `"human"`, and blocks, polling
that same file. A separate process, `OperatorConsole`, reads the file,
connects to the *same* live browser via `connectOverCDP`, and lets the
human issue `click`/`fill` commands through the identical `Locator`
vocabulary automation uses. Every action is appended to
`ControlState.humanActions`. Typing `resume` or `abort` writes a resolution
back to the file, which unblocks the waiting automation process.

This was proven end to end on both paths, not just designed. On the replay
side, a run was made to hit a `SESSION_EXPIRED` outcome flagged `escalate:
true`; it paused, a separate process attached over CDP to the exact browser
instance the first process had launched, and both a "resume" and an
"abort" resolution were tested and correctly reflected in the final
result. On the discovery side, a run given an impossible goal (a nonexistent
member) correctly called `finish_stuck`, paused, and correctly ended as
`stuck` once the operator aborted.

What was mocked, per the brief's explicit scope note: the operator's UI is
a CLI, not a real-time co-browsing web console. The mechanism underneath it
- shared live session over CDP, file-backed control ownership, a
structured intervention/resolution record - is real, not a stub.

## 6. Safety

`AllowlistConfig` is enforced inside `PlaywrightSurface` itself, at the
lowest layer, not as a check the agent loop remembers to call: every
`navigate` is checked against `allowedDomains`/`allowedRouteGlobs`, and
every action type against `allowedActionTypes`, so an action physically
cannot reach the browser if it falls outside policy.

Risky actions (`RiskLevel.RISKY`, matched by patterns like `click:*Submit*`
against an action's name, or set explicitly on a step) are handled
differently by phase, via `RiskPolicy`:

- During discovery, a human developer is directly watching the run in real
  time, so risky actions are allowed and logged loudly
  (`allowRiskyInDiscovery`), rather than blocked.
- During replay - the unattended, production path - a risky step is only
  executed automatically if `Capability.reviewed` is true. If not, the
  executor escalates to a human for an explicit go/no-go on that specific
  invocation, rather than either silently running it or failing outright.

This satisfies the brief's "block, require confirmation, or flag" options
with a concrete choice: flag always (in the schema, via `riskLevel`), block
by default in unattended replay, with confirmation available through the
same escalation path used for hard failures.

Data handling: `Capability` never stores literal parameter values discovered
during a run - the recorder templatizes any literal that matches a supplied
input value into `{{param_name}}`, so a member ID typed during discovery is
never frozen into the reusable artifact. Before a `DiscoveryTranscript` is
written to disk, `Redactor.scrubTranscript` strips currency amounts,
account-number-shaped tokens, bearer tokens, and SSN-shaped strings out of
its free-text fields (each step's rationale and error text, and the run's
final summary) - this was verified by running a real discovery session and
confirming the saved transcript shows `$[AMOUNT_REDACTED]` in place of the
actual balance the model mentioned in its reasoning. `InputParamSpec` and
`OutputFieldSpec` both carry a `sensitive` flag for fields a caller should
never log in plaintext.

One deliberate exception: the transcript's `declaredOutputs` map (the
actual extracted value, e.g. the savings balance the run was asked to find)
is not scrubbed. Redacting the very thing a run was asked to retrieve would
make the transcript useless as evidence of what discovery actually found.
The `sensitive` flag on the corresponding `OutputFieldSpec` is what
protects that value at the replay/production layer, where a caller can
choose not to log it.

Limits, honestly: the regex-based redaction is a backstop, not a guarantee
- it will not catch every possible sensitive string shape. The allowlist is
domain/route/action-type granularity, not field-level (it does not, for
example, distinguish "fill this safe field" from "fill this sensitive
field" within the same route).

## 7. Cuts

- **Outcome taxonomy is hand-authored, not auto-discovered.** A single
  successful discovery run cannot see branches it never walked. In
  production this would come from either a human reviewer annotating the
  artifact before approval, or a set of deliberately adversarial follow-up
  discovery probes (bad input, wrong permissions) run against the same
  flow. Neither was built; the taxonomy for the one demo capability was
  written by hand to match the mock app's actual known behavior.
- **The operator console is a CLI, not a co-browsing UI**, per the brief's
  explicit scope note. The underlying handoff mechanism is real and proven
  on both the discovery and replay paths; the interface a human uses to
  type commands is intentionally minimal, and currently supports only
  `click`/`fill`/`resume`/`abort` (no `navigate` command, which meant one
  manual test had to work around a page with nothing clickable on it by
  aborting rather than fixing the page and resuming).
- **After a human resolves a replay-side escalation, the executor
  re-checks the success condition once rather than resuming step by
  step.** This is a real, deliberate simplification: the schema has no
  concept of "resume from step N" for this kind of failure, only for
  `RECOVERABLE` outcomes.
- **Multi-tenant and desktop support are designed, not built** (also
  explicitly out of scope per the brief). Section 4 describes the concrete
  seam (`Surface`, `TargetFingerprint`) that would carry that work.
- **No drift detection or confidence scoring was built** - see section 4
  for the credible next step using existing `HARD_FAILURE` signal.
- **The mock target app uses in-memory data, not a database.** The app
  itself is a prop for exercising the automation system, not the thing
  being evaluated, and persistence was not worth the added setup friction.
- **Assisted fallback (bounded LLM recovery on a single failed replay
  step) was not built.** Given the choice of at most one or two stretch
  goals, effort went instead into making the two load-bearing pieces
  (schema, replay/error handling) and the escalation mechanism genuinely
  solid, wired end to end, and evidence-backed rather than adding a third
  partially-built feature.
- **What I'd build next with more time:** a small confidence/approval
  workflow (score a capability by replay success rate across N runs, gate
  `reviewed=true` on that score crossing a threshold rather than a manual
  flag), a `navigate` command in the operator console, and one adversarial
  discovery pass per capability specifically to populate `knownOutcomes`
  automatically instead of by hand.
