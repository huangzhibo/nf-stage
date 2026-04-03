/*
 * Copyright 2025, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.plugin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

import com.google.common.hash.Hashing
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Global
import nextflow.Session
import nextflow.extension.CH
import nextflow.script.ChannelOut
import nextflow.script.WorkflowDef
import nextflow.util.HashBuilder

/**
 * Intercepts named workflow execution to provide
 * stage-level archiving and resume capabilities.
 *
 * Discovered automatically by Nextflow via pf4j ExtensionPoint.
 */
@Slf4j
@CompileStatic
class StageObserver implements WorkflowInterceptor {

    private Session session

    StageArchive archive

    private Path cachedStagesTsv

    private final ConcurrentHashMap<Object, String> digestRegistry = new ConcurrentHashMap<>()

    // stage name -> digest for stages archived in this run
    final ConcurrentHashMap<String, String> archivedStages = new ConcurrentHashMap<>()

    private boolean initialized

    private boolean headerWritten

    private void init() {
        if( initialized ) return
        this.session = Global.session as Session
        final config = (session.config.navigate('stage') ?: Collections.emptyMap()) as Map
        final archiveRoot = (config.get('archiveRoot') ?: '.nf-stage-archive') as String
        this.archive = new StageArchive(session.baseDir.resolve(archiveRoot) as Path)
        this.cachedStagesTsv = Path.of('cached-stages.tsv')
        this.initialized = true
        log.debug "Stage interceptor initialized, archive root: ${archiveRoot}"
    }

    @Override
    Object intercept(Object workflowObj, Object[] args, Closure proceed) {
        init()
        final workflow = (WorkflowDef) workflowObj
        final name = workflow.name
        final digest = computeDigest(workflow, args)
        log.debug "Stage ${name} digest: ${digest}"

        final cached = archive.findArchive(name, digest)
        if( cached != null ) {
            log.info "Restoring stage ${name} from archive"
            final result = archive.restore(session, cached)
            registerChannels(result, digest)
            writeCachedStageTsv(cached)
            return result
        }

        final result = proceed.call()

        if( result instanceof ChannelOut ) {
            archive.archive(session, name, digest, (ChannelOut) result)
            registerChannels((ChannelOut) result, digest)
            archivedStages.put(name, digest)
        }

        return result
    }

    private String computeDigest(WorkflowDef workflow, Object[] args) {
        final hasher = Hashing.sha256().newHasher()
        hasher.putUnencodedChars(workflow.name)
        final builder = new HashBuilder().withHasher(hasher)
        for( final arg : args ) {
            if( CH.isChannel(arg) ) {
                final upstream = digestRegistry.get(arg)
                hasher.putUnencodedChars(upstream ?: 'untracked')
            }
            else {
                builder.with(arg)
            }
        }
        return "sha256:${hasher.hash().toString()}"
    }

    private void registerChannels(ChannelOut output, String digest) {
        for( int i = 0; i < output.size(); i++ ) {
            digestRegistry.put(output.get(i), digest)
        }
    }

    private void writeCachedStageTsv(Map stageData) {
        final stage = stageData.get('stage') as String
        final digest = stageData.get('compatibility_digest') as String
        final taskHashes = stageData.get('task_hashes') as List ?: []
        final archivedAt = stageData.get('created_at') as String
        final archivePath = archive.archivePath(stage, digest)

        if( !headerWritten ) {
            Files.write(cachedStagesTsv, "stage\tdigest\ttask_count\tarchive_path\tarchived_at\n".getBytes())
            headerWritten = true
        }

        final line = "${stage}\t${digest}\t${taskHashes.size()}\t${archivePath}\t${archivedAt}\n"
        Files.write(cachedStagesTsv, line.getBytes(), StandardOpenOption.APPEND)
        log.debug "Wrote cached stage entry: ${stage}"
    }
}
