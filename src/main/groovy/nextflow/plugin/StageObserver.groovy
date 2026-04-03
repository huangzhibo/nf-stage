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

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.script.ChannelOut
import nextflow.script.WorkflowDef
import nextflow.script.WorkflowInterceptor
import nextflow.trace.TraceObserver

/**
 * Intercepts named workflow execution to provide
 * stage-level archiving and resume capabilities.
 */
@Slf4j
@CompileStatic
class StageObserver implements TraceObserver, WorkflowInterceptor {

    private Session session

    private StageDigest stageDigest

    private StageArchive stageArchive

    StageObserver(Session session) {
        this.session = session
        this.stageDigest = new StageDigest()
        final archiveRoot = session.config.navigate('stage.archive_root', '.nf-stage-archive') as String
        this.stageArchive = new StageArchive(session.baseDir.resolve(archiveRoot) as Path)
    }

    // -- TraceObserver lifecycle --

    @Override
    void onFlowCreate(Session session) {
        log.debug "Registering stage workflow interceptor"
        WorkflowDef.interceptor = this
    }

    @Override
    void onFlowComplete() {
        log.debug "Unregistering stage workflow interceptor"
        WorkflowDef.interceptor = null
    }

    // -- WorkflowInterceptor --

    @Override
    Object intercept(WorkflowDef workflow, Object[] args, Closure proceed) {
        final name = workflow.name
        final digest = stageDigest.computeDigest(workflow, args)
        log.debug "Stage ${name} digest: ${digest}"

        // check for existing archive
        final archivePath = stageArchive.findArchive(name, digest)
        if( archivePath != null ) {
            log.info "Restoring stage ${name} from archive: ${archivePath}"
            final result = stageArchive.restore(session, archivePath, workflow)
            stageDigest.registerChannels(result, digest)
            return result
        }

        // no archive found, execute normally
        final result = proceed.call()

        // archive the result
        if( result instanceof ChannelOut ) {
            final channelOut = (ChannelOut) result
            stageArchive.archive(session, name, digest, channelOut, {
                log.info "Stage ${name} archived successfully"
            })
            stageDigest.registerChannels(channelOut, digest)
        }

        return result
    }
}
