package nextflow.plugin

import groovy.transform.CompileStatic
import nextflow.config.spec.ConfigOption
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.ScopeName
import nextflow.script.dsl.Description

/**
 * Configuration scope for the nf-stage plugin.
 */
@ScopeName("stage")
@Description("The `stage` scope controls stage-level archiving and reuse.")
@CompileStatic
class StageConfig implements ConfigScope {

    @ConfigOption
    @Description("Root directory for stage archives (default: `.nf-stage-archive`).")
    final String archiveRoot

    @ConfigOption
    @Description("File path for cached stages TSV (default: `cached-stages.tsv`).")
    final String cachedStagesFile

    @ConfigOption
    @Description("Whether this run may write new archives (default: `true`). Set `false` for read-only reuse of a shared archive.")
    final Boolean writable

    /* required by extension point */
    StageConfig() {}

    StageConfig(Map opts) {
        this.archiveRoot = opts.archiveRoot as String
        this.cachedStagesFile = opts.cachedStagesFile as String
        this.writable = opts.writable as Boolean
    }
}
