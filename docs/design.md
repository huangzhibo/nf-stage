# nf-stage Design

**English** | [中文](zh/design.md)

## Goals

- Reuse already-archived stages for follow-up analysis after historical `work/` has been deleted
- Keep long-term storage only for files that are truly needed for cross-stage reuse or final delivery
- Automatically decide which stages can be skipped when users change parameters

## Core Concepts

- **Stage**: a named workflow with explicit inputs and stable outputs
- **Stage archive**: an immutable asset persisted after a stage completes successfully; contains `stage.json` and the `emit:` outputs
- **Reuse artifact**: by default, the stage's `emit:` outputs — what later runs, workflow outputs, and fan-ins consume
- **Compatibility digest (`compatibility_digest`)**: the SHA-256 that decides whether a historical archive can be reused; derived from stage name + static parameters + channel-input contents

Any input change → a different digest → re-execution. Upstream changes propagate to downstream digests naturally through channel data. When a stage's implementation changes, adding an explicit `stage_version` parameter to its named workflow invalidates old archives (the parameter enters the digest).

## Hard Constraints

1. When no archive matches, the stage and everything downstream must re-execute
2. When the `compatibility_digest` matches, reuse the existing archive — do not write a new one
3. A directory-type `emit:` containing more than 1000 files should cause a runtime error (guards against many-small-files blowing up the archive)

## Reuse Decision Flow

1. Each named workflow invocation is intercepted
2. Channel arguments are cloned; the actual input data is collected
3. The `compatibility_digest` is computed
4. The plugin looks up `archiveRoot/<stage>/<digest-prefix-16>/stage.json`
5. Found with `integrity.status == "ok"` → emit archived outputs, skip execution
6. Not found → execute normally and write the archive when the stage completes

## stage.json Schema

```json
{
  "schema_version": "v1",
  "stage": "ALIGN",
  "compatibility_digest": "sha256:db6fe4bca85f1cf2...",
  "created_at": "2026-04-10T14:06:13.530127+08:00",
  "integrity": { "status": "ok" },
  "channels": {
    "bam_ch": {
      "type": "queue",
      "items": [
        [
          { "type": "value", "data": { "id": "SAMPLE1" } },
          { "type": "file",  "name": "sample1.bam", "checksum": "sha256:...", "size": 123456 }
        ]
      ]
    }
  },
  "task_hashes": ["a6/18ddf1", "1c/fb5040"]
}
```

| Field | Description |
|-------|-------------|
| `schema_version` | Fixed `"v1"` |
| `stage` | Named workflow name |
| `compatibility_digest` | Full SHA-256 used for reuse decisions |
| `created_at` | Archive time (ISO 8601) |
| `integrity.status` | `"ok"` means the archive is complete; any other value disqualifies it from reuse |
| `channels` | Serialized data for each `emit:` channel |
| `task_hashes` | Nextflow task hashes touched by this stage, back-filled after the workflow completes |

Elements inside `channels[name].items` come in two shapes:

- `{ "type": "file", "name": "...", "checksum": "sha256:...", "size": N }`
- `{ "type": "value", "data": <any> }`

## Single Writer, Many Readers

When multiple runs share the same `archiveRoot`, the current implementation supports only "single-writer / many-readers": one designated writer produces the archive for a given digest, and everyone else consumes it as a reader.

- **Writer**: `writable = true` (default) — reuse on hit; execute and archive on miss
- **Reader**: `writable = false` — reuse on hit; on miss, execute normally but do **not** write the archive and do **not** back-fill `task_hashes`

**Startup probe**: when `writable = true`, the plugin writes a probe file into `archiveRoot` at startup to verify write permission. If that fails, it logs a warning and silently degrades to read-only mode, preventing a mid-pipeline failure caused by an unwritable archive.

**Recommendation**: ship user-facing templates with `writable = false`, reserve `writable = true` for the centralized CI/warm-up run, and back the boundary up with storage-layer ACLs that restrict writes on the shared `archiveRoot`.

## Proposals (Not Yet Implemented)

- [start-stage.md](start-stage.md) — `startStage`: start execution from a designated stage (design draft, to be implemented on demand)
