# nf-stage

**English** | [中文](README.zh-CN.md)

A Nextflow plugin that provides **stage-level archiving and reuse**. After deleting `work/`, completed stages can be restored from the archive so you don't recompute them.

## Requirements

A customized Nextflow build that exposes the `WorkflowInterceptor` extension point. The official distribution does not support this yet.

## Install

```bash
make assemble    # build build/distributions/nf-stage-0.1.0.zip
make install     # install into ~/.nextflow/plugins/
make test        # run unit tests
```

## Configuration

`nextflow.config`:

```groovy
plugins {
    id 'nf-stage@0.1.0'
}

stage {
    archiveRoot      = '.nf-stage-archive'   // archive root
    cachedStagesFile = 'cached-stages.tsv'   // reuse record file
    writable         = true                  // whether this run may write new archives
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `archiveRoot` | String | `.nf-stage-archive` | Archive root; point at shared storage (NFS/Lustre/S3) for cross-host reuse |
| `cachedStagesFile` | String | `cached-stages.tsv` | Per-run record of which stages were reused |
| `writable` | Boolean | `true` | Whether this run may write new archives; set `false` for read-only reuse (see [design.md § Single Writer, Many Readers](docs/design.md#single-writer-many-readers)) |

## Writing Reusable Pipelines

nf-stage treats a named workflow as the sole stage boundary:

- Declare inputs with `take:` and outputs with `emit:`
- Files/values in `emit:` are the stage's long-lived archived outputs
- Intermediate files inside a stage that are not in `emit:` are transient and may be removed together with `work/`
- Stage inputs should come from an upstream stage's `emit:` or a stable external input (`params`, a static samplesheet). **Do not** depend on the output of a bare process that lives outside any stage — bare processes are not archived, so reusing a downstream stage will re-execute them

Minimal example:

```nextflow
include { PREPARE } from './workflows/prepare'
include { ALIGN }   from './workflows/align'
include { CALL }    from './workflows/call'

workflow {
    main:
    prepared = PREPARE(params.input)
    aligned  = ALIGN(prepared.fastq_ch, params.reference)
    called   = CALL(aligned.bam_ch, params.reference)

    publish:
    variants = called.vcf_ch
}

output {
    variants { path '.' }
}
```

A complete example lives under [examples/wgs-demo](examples/wgs-demo).

## How It Works

Every named workflow call is intercepted; the plugin computes a SHA-256 digest over the inputs. On a hit, archived outputs are emitted directly; on a miss, the stage executes normally and the result is archived. See [design.md § Reuse Decision Flow](docs/design.md#reuse-decision-flow).

Three scenarios:

| Scenario | Mechanism | Prerequisite |
|----------|-----------|--------------|
| Retry a failed run | Nextflow `-resume` | `work/` still present |
| Rerun after deleting `work/` | nf-stage stage reuse | archive still present |
| Skip upstream stages manually | Pipeline author branches in the entry workflow | User supplies input for the starting stage |

## Outputs

Archive layout:

```text
<archiveRoot>/<stage>/<digest-prefix-16>/
├── stage.json      # metadata + serialized channel data
├── 0/              # files for emission 0
└── 1/              # emission 1 ...
```

`cached-stages.tsv`:

```
stage   digest              task_count  archive_path                                archived_at
ALIGN   sha256:db6fe4b...   4           .nf-stage-archive/ALIGN/db6fe4bca85f1cf2    2026-04-10T14:06:13+08:00
```

## More

- [docs/design.md](docs/design.md) — design document (terms, schema, compatibility digest, single-writer/many-readers, proposals)
