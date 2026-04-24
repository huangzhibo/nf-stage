# startStage — Start Execution from a Designated Stage (Proposal)

**English** | [中文](zh/start-stage.md)

Status: design draft, to be implemented on demand. This is a plugin-level capability, distinct from the "skip upstream stages manually" row in the README's three-scenario table (where the pipeline author branches inside the entry workflow).

## 1. Scenario

Cluster A has insufficient capacity; intermediate stage outputs need to be synced to cluster B to continue analysis from that stage onward. The volume of data to sync should be as small as possible.

## 2. Configuration

```groovy
stage {
    startStage = 'CALL'  // start from CALL; preceding stages are restored from archives
}
```

`startStage` designates the starting stage for this run. All preceding stages are restored from their archives (digest matching is bypassed) instead of being executed.

## 3. Execution Logic

With a `PREPARE → ALIGN → CALL` pipeline and `startStage = 'CALL'`:

1. At initialization, the plugin reads `startStage` and marks the target stage
2. Intercept PREPARE: rebuild channel outputs from `stage.json`, skip execution
3. Intercept ALIGN: same as above
4. Intercept CALL: normal flow (compute digest, look up archive, execute on miss)

Skipped stages do not compute a digest; they rebuild output channels directly from the `channels` block of `stage.json`. Since `stage.json` already records channel names, types, and emission data, the existing `emitArchivedData()` routine can be reused as-is.

## 4. Data That Must Be Synced

| Data | Size | Notes |
|------|------|-------|
| Samplesheet CSV | small | `Channel.fromPath()` requires the file to exist |
| `stage.json` for each stage before `startStage` | small | metadata only, used to rebuild channels |
| Archive files for the stage immediately before `startStage` | depends on the stage's output | processes in `startStage` need to read these files |

The key insight: archive files for earlier upstream stages do not need to be synced. `Path` objects rebuilt from `file`-typed elements in `stage.json` are never actually read, because the consumers of those paths are themselves skipped. `file()` merely constructs a `Path` reference without checking existence.

## 5. Implementation Notes

- Add a `startStage` option to `StageConfig`
- In `intercept()`, detect whether the current stage comes before `startStage`; if so, restore from the corresponding `stage.json` under `archiveRoot` — do not execute, do not archive
- Once `startStage` is reached, fall back to the normal interception path
- Skipped stages are not written to `cached-stages.tsv`
