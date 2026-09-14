# Configuration — where a setting belongs

There are two config mechanisms, and picking the wrong one is how the surface gets messy. This page is
the rule; read it before adding a setting.

---

## The boundary

| | **Knobs** → `application.properties` | **Content** → `src/main/resources/config/*.json` |
|---|---|---|
| Shape | a number, a boolean, a URL, a duration | prose, templates, lists of field names |
| Varies by | deployment (dev vs prod) | task, connector, or user |
| Examples | `app.indexing.lease-seconds`, `app.search.rrf-k`, `${GEMINI_API_KEY:}` | prompts, embed-context fields, prompt locators |
| Read via | `@ConfigProperty` | `JsonConfigLoader` |

**Ask two questions.** Is it prose or a list that a human will want to read and edit? Does the *same*
setting take different values in different scenarios — per task, per connector? Two yeses means JSON.
Otherwise it is a knob.

### Why not put everything in JSON

Quarkus config gives three things a hand-rolled JSON loader would have to reimplement:

- **Env-var indirection.** `${GEMINI_API_KEY:}` resolves at startup. Secrets must never sit in a
  checked-in file, so anything credential-shaped has no choice.
- **Profiles.** `%dev.app.indexing.poll-interval=1s` works with no code.
- **Type conversion and startup validation.** `app.embedding.dimension` has deliberately *no* default so
  an absent property fails startup (invariant 5) — the JSON loader has no equivalent.

### Why not put everything in properties

A prompt flattened into properties keys becomes `app.prompts.answer.system.line1`, which is unreadable,
uneditable and cannot be categorised. And a flat key can only ever say one thing: the moment a second
task or a second connector needs a different value, you are choosing between a rename and a duplicate.

---

## Content files

### `config/prompts.json`

Two sections. `prompts` is the text; `tasks` is the thin wiring saying which prompt, which model, and
which budgets a given job runs with.

This file holds the **bundled** tasks only. User-written tasks live in the Mongo `tasks` collection
and are layered over it at runtime — the split, and why the two validate at different times, is in
[`tasks.md`](./tasks.md). The rule for which mechanism a new task belongs in: if the application
itself depends on it, it is bundled and read-only; if a person authors it for their own digest, it is
a row.

```jsonc
{
  "prompts": {
    "answer": {
      "description": "why this prompt exists, for whoever edits it next",
      "variables": ["today", "fence", "truncationMarker"],
      "system": "… Today is {{today}}. …",
      "user": "Question: {{query}}\n\nSources:\n{{sources}}"
    }
  },
  "tasks": {
    "answer": {
      "name": "Answer questions",        // shown in a picker; falls back to the id
      "prompt": "answer",
      "llmProfile": "answer",
      "contextChars": 24000,
      "maxSources": 10
    },
    "job-fit": {
      "annotates": "postings",           // array in a JSON reply describing sources one by one
      "digest": true                     // may a digest be pointed at this? opt in, not out
    }
  }
}
```

Two task keys exist for the digest read path. **`annotates`** names the array in a JSON reply whose
elements each carry a `source` number, which is how a digest joins the reply back onto the results it
ran over — see [`digests.md`](./digests.md). **`digest`** opts a task in to being offered in the
console: `answer` and `document-facets` are machinery the read path runs for itself, and offering them
would invite a digest that quietly does nothing useful.

**Prompts are keyed by task, never by model.** A prompt naming a model cannot be reused when the model
changes and cannot be shared by two features on different models. Model choice lives in
`app.llm.profile.*`, which a task *references* by name — so a task can carry a prompt but never model
settings. `PromptCatalogTest.noPromptMentionsAModelOrProvider` enforces this; it fails if a prompt
mentions `llama`, `gemini`, `groq`, `claude`, `openai`, `bge` or similar.

> `app.embedding.onnx.query-instruction` is **not** a prompt and stays a property. It is a model's
> documented input format (BGE's published wording), meaningless to any other model — the exact category
> the rule above exists to separate out.

**Budgets belong to the task.** `contextChars` and `maxSources` are meaningless globally: answering and a
future summarisation want different values. As flat keys there is one value for the whole application, so
the second task needing a different one forces a rename or a duplicate key.

**Validation is a startup failure**, not a warning. A task pointing at a deleted prompt, an undeclared
`{{variable}}`, or a declared variable the text never uses all stop the application. Each of those
degrades *silently* at runtime — the model receives a literal `{{today}}` or no instructions at all, and
the answer looks fine while being ungrounded. A hand-edited file is exactly where such mistakes come
from, so they must be loud.

`app.prompts.path` replaces the bundled file wholesale (replace, not merge — a half-overridden catalogue
is harder to reason about than "your file, or ours"). That is the seam for user-supplied prompts.

### `config/field-sets.json`

Named lists of metadata field names, optionally scoped per connector.

```jsonc
{
  "fieldSets": {
    "embedContext": {
      "default": ["title"],
      "bySourceType": { "GMAIL": ["title", "from"], "GOOGLE_DRIVE": ["title", "headingPath"] }
    },
    "promptLocator": { "default": ["headingPath", "rowRange", "sheet", "page"] }
  }
}
```

Two callers today: what gets prefixed before embedding (`FieldSets.EMBED_CONTEXT`, used by
`IndexingRunner`) and what locates a source in a prompt (`FieldSets.PROMPT_LOCATOR`, used by
`AnswerPromptBuilder`). The right answer differs per caller *and* per connector — a Gmail chunk is best
identified by sender, a Drive document by heading path.

A per-connector list wins **entire**; lists are not merged. A connector that overrides is stating the
whole answer, which is easier to reason about than a union. An unknown set name warns and returns empty
rather than throwing, and an unrecognised `SourceType` is ignored with a warning so config can be written
ahead of the connector that will use it.

> **Changing `embedContext` requires a re-index.** Existing vectors were built from a different string.

---

## Resolution tiers, and which pattern to copy

Four resolvers exist. Match the one whose shape fits rather than inventing a fifth:

| Pattern | Example | Use when |
|---|---|---|
| **Per-leaf overlay** | `ChunkingSpecResolver` — per-knowledge nullable fields over `app.chunking.*` | a caller should override *some* fields and inherit the rest |
| **Whole-value tier** | `ScheduleResolver` and `RetentionResolver` (knowledge → connector → global), `FieldSets` (connector → default) | a tier states the complete answer or says nothing |
| **Dynamic by name** | `LlmProfiles` reading `app.llm.profile.<name>.*` | adding an instance should need config only, no code |
| **Stored-entity over config** | `RateLimitPolicies` — `Connection.rateLimit` over `app.ratelimit.connector.<TYPE>.rules` | the value is **user-editable at runtime**, so it lives in Mongo and config only supplies the fallback |

All three end in an immutable **resolved value record** — `ChunkingSpec`, `SyncSchedule`, `TaskSpec` —
whose compact constructor does the clamping. That is what keeps tests CDI-free: a test builds the record
directly instead of standing up a container, and every path that produces one is sanitised.

> **A task's *shape* is config too.** `sourceText` (`CHUNK` / `ENTITY`) and `responseFormat`
> (`TEXT` / `JSON_OBJECT`) sit on the task entry in `prompts.json` alongside its prompt and budgets, so
> a task that must judge whole documents and reply in JSON is a config entry rather than a second code
> path. Both default to the answering behaviour, so an entry naming neither is unchanged. An
> unrecognised value is a **boot failure**, not a silent default — a typo would otherwise leave the task
> quietly running the wrong shape.
>
> `responseFormat` is a request the endpoint may ignore, so a caller still parses defensively
> (`JsonReplies`). Treating it as a guarantee is how a task that works against one vendor breaks
> against another.

> **A per-request override belongs on the query, not in config.** `app.search.max-chunks-per-entity`
> and the dedupe knobs are deployment-wide defaults, but whether a *particular* search wants one result
> per document, or wants duplicates grouped, is a property of that call — so both are fields on
> `SearchQuery` that fall back to the configured value. The rule of thumb: config sets the default,
> the request states the exception.

> **A user-editable setting is a third thing, and it is neither a knob nor content.** A rate limit is
> a number, which reads like an `application.properties` knob — but the user changes it from the
> console at runtime, so it has to be persisted per entity and config can only be its default. The
> giveaway is *who edits it and when*: an operator editing a file at deploy time is a knob; a user
> editing a form is entity data. `RateLimitPolicies` therefore reads the stored value first and falls
> back to config, and holds no state of its own — the caller passes the resolved policy on every call,
> so an edit takes effect on the next grab with no cache to invalidate.
>
> Note the asymmetry it forces on PATCH: absent already means "unchanged", so *clearing* the value
> needs an explicit empty (`{"rateLimit": {"rules": []}}`). Any future user-editable collection will
> hit the same problem — decide how removal is spelled before shipping the field.

> **A behaviour the code already knows belongs in the code, with config as the override.**
> `app.indexing.refetch-on-reindex` defaults to `auto`, which means "ask the connector"
> (`SourceConnector#defaultReindexMode`). Whether a re-index has to fetch content again is a fact
> about where that connector puts its bytes, so a per-connector property would be a list of answers
> the connector already knows and a new connector would ship broken until someone remembered to add a
> line. Config exists here only for the two cases code cannot settle: forcing it on while debugging,
> and turning it off to make a purged staged file fail loudly. `RefetchPolicy` is the one place that
> reads both.

> **A key composed from an identifier is the "dynamic by name" tier, and it has a cost.**
> `OAuthClients` reads `app.oauth.<providerId>.client-id` / `.client-secret` by building the name at
> runtime, the same shape as `LlmProfiles` — which is precisely what lets a new OAuth provider ship two
> properties and no resolution code. The price is that `ConfigDefaultsTest`'s reverse check cannot see
> the key: it scans for string literals, and a composed name is not one, so every such key has to be
> listed in `REACHED_ANOTHER_WAY` or it reports as rename debris. Composing a name is a real trade —
> take it when instances are open-ended (providers, profiles), not to save one injection point.

> **A tier may legitimately resolve to nothing.** `RetentionResolver` returns `null` when no tier sets
> a window, and callers must read that as *never expire* rather than *expire immediately*. Shipping
> `app.retention.default-period` blank is a deliberate choice, not an oversight — a global default
> there would start deleting every document corpus in the deployment.

---

## Guardrails

`ConfigDefaultsTest` checks both directions and is worth understanding before you trust it:

- every `@ConfigProperty(defaultValue = …)` agrees with `application.properties`, which is the tiebreaker;
- every `app.*` key shipped is actually read, with `REACHED_ANOTHER_WAY` allowlisting the ones resolved
  dynamically (`app.llm.profile.*`) or interpolated into `@Scheduled(every = "{…}")`;
- `app.embedding.dimension` carries no code default anywhere (invariant 5);
- and the scan itself matches the codebase.

That last check exists because the original version of this test **enforced nothing for its entire life**:
its pattern required `@ConfigProperty( name = … )` with spaces inside the parens, a formatting the
codebase never used, so it matched zero of 74 annotations and passed vacuously while two real drifts sat
in the tree. `theScanActuallyMatchesTheCodebase` and `reportsAPlantedDrift` are there so a guard cannot
go quiet again.

**Known blind spot:** a non-literal default such as `defaultValue = RecursiveCharacterChunkingStrategy.NAME`
cannot be compared by a text scan and is invisible to these checks.

---

## Where this treatment should go next

Ranked by value. Each is the same idea: a thing that is one instance of a category should be named and
externalised rather than hardcoded.

1. **Retrieval presets.** `app.search.lexical.*` + `app.search.rrf.*` + `candidate-multiplier` are eight
   keys that take different values in different scenarios — the textbook case. Named presets
   (`balanced` / `precise` / `recall`) selectable per request would mirror `LlmProfiles` exactly and let
   retrieval settings be A/B'd without a redeploy. The console already sends a `mode`; a `preset` beside
   it is the same idea one level up.
2. **Lexical field boosts** (`text,title^2`) — a field set with weights; folds into `field-sets.json`
   once presets exist.
3. **Chunking presets.** `ChunkingSpec` is already the resolved-record half; the missing half is named
   presets (`prose` / `table` / `code`) with `Knowledge.Config.ChunkingSettings` selecting one instead of
   restating four numbers.
4. **Connector defaults.** `SourceConnector.defaultSchedule()` is the only one today; timeouts, page
   sizes and `max-file-bytes` are all per-connector in nature but currently flat keys.
5. **Backend user-facing copy.** The console centralises this in `src/config/labels.ts`; the backend's
   equivalents (`NO_SOURCES`, error messages) are scattered literals.

Not worth it: infrastructure knobs, and anything env-backed.
