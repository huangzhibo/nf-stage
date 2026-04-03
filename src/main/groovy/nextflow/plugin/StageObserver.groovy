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
import java.util.concurrent.atomic.AtomicInteger

import com.google.common.hash.Hashing
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowWriteChannel
import nextflow.Channel
import nextflow.Global
import nextflow.Session
import nextflow.extension.CH
import nextflow.extension.DataflowHelper
import nextflow.script.ChannelOut
import nextflow.script.WorkflowDef
import nextflow.util.CacheHelper
import nextflow.util.HashBuilder

/**
 * Intercepts named workflow execution to provide
 * stage-level archiving and resume capabilities.
 *
 * Uses a unified clone-channel approach: all channel args are
 * cloned, values collected via subscription, and digest computed
 * from actual content. No tracked/untracked distinction.
 */
@Slf4j
class StageObserver implements WorkflowInterceptor {

    private Session session

    StageArchive archive

    private Path cachedStagesTsv

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
        final declaredOutputs = workflow.@declaredOutputs as List<String>

        // separate channel args (create clones) and static args (hash immediately)
        final channelInfos = new ArrayList<Map>()
        final staticHasher = Hashing.sha256().newHasher()
        staticHasher.putUnencodedChars(name)
        final builder = new HashBuilder().withHasher(staticHasher)

        for( int i = 0; i < args.length; i++ ) {
            if( CH.isChannel(args[i]) ) {
                final clone = CH.create()
                channelInfos.add([index: i, original: args[i], clone: clone])
                args[i] = clone
            }
            else {
                builder.with(args[i])
            }
        }
        final staticDigestPart = staticHasher.hash().toString()

        // proceed — processes register with clone channels as input
        final realOutput = proceed.call()

        // create placeholder output channels (returned to downstream)
        final placeholderChannels = new LinkedHashMap<String, DataflowWriteChannel>()
        for( final outName : declaredOutputs ) {
            final realCh = ((ChannelOut) realOutput).getProperty(outName)
            placeholderChannels.put(outName, CH.create(CH.isValue(realCh)))
        }
        final placeholder = new ChannelOut(placeholderChannels)

        if( channelInfos.isEmpty() ) {
            // no channel args — pure static input, decide immediately
            handleResult(name, "sha256:${staticDigestPart}", realOutput,
                        placeholderChannels, declaredOutputs, channelInfos, null)
        }
        else {
            // subscribe to all original channels, collect values, then decide
            final collected = new LinkedHashMap<Integer, List<Object>>()
            final pending = new AtomicInteger(channelInfos.size())

            for( final info : channelInfos ) {
                final idx = info.index as int
                collected.put(idx, Collections.synchronizedList(new ArrayList()))
                final readCh = CH.getReadChannel(info.original)

                DataflowHelper.subscribeImpl(readCh, [
                    onNext: { Object value ->
                        collected.get(idx).add(value)
                    } as Closure,
                    onComplete: {
                        if( pending.decrementAndGet() == 0 ) {
                            final contentHasher = Hashing.sha256().newHasher()
                            contentHasher.putUnencodedChars(staticDigestPart)
                            for( final entry : collected.entrySet() ) {
                                // use SHA256 mode so file paths with same content produce same hash
                                new HashBuilder().withHasher(contentHasher).withMode(CacheHelper.HashMode.SHA256).with(entry.value)
                            }
                            final digest = "sha256:${contentHasher.hash().toString()}"

                            handleResult(name, digest, realOutput,
                                        placeholderChannels, declaredOutputs,
                                        channelInfos, collected)
                        }
                    } as Closure
                ] as Map<String, Closure>)
            }
        }

        return placeholder
    }

    private void handleResult(String name, String digest, Object realOutput,
                             Map<String, DataflowWriteChannel> placeholderChannels,
                             List<String> declaredOutputs,
                             List<Map> channelInfos,
                             Map<Integer, List<Object>> collected) {
        log.debug "Stage ${name} digest: ${digest}"

        final cached = archive.findArchive(name, digest)
        if( cached != null ) {
            log.info "Restoring stage ${name} from archive"
            emitArchivedData(cached, placeholderChannels)
            for( final info : channelInfos ) {
                ((DataflowWriteChannel) info.clone).bind(Channel.STOP)
            }
            writeCachedStageTsv(cached)
        }
        else {
            log.info "Executing stage ${name} (no archive found)"
            // forward realOutput → placeholder
            forwardOutputs(realOutput as ChannelOut, placeholderChannels, declaredOutputs)
            // feed collected values to clones → processes start
            if( collected != null ) {
                for( final info : channelInfos ) {
                    final idx = info.index as int
                    for( final value : collected.get(idx) ) {
                        ((DataflowWriteChannel) info.clone).bind(value)
                    }
                    ((DataflowWriteChannel) info.clone).bind(Channel.STOP)
                }
            }
            archive.archive(session, name, digest, realOutput as ChannelOut)
            archivedStages.put(name, digest)
        }
    }

    private void emitArchivedData(Map stageData, Map<String, DataflowWriteChannel> placeholderChannels) {
        final stageName = stageData.get('stage') as String
        final digest = stageData.get('compatibility_digest') as String
        final basePath = archive.archivePath(stageName, digest)
        final channelsData = stageData.get('channels') as Map<String, Map>

        for( final entry : channelsData.entrySet() ) {
            final outName = entry.key
            final chData = entry.value
            final items = chData.get('items') as List<List>
            final isValue = chData.get('type') == 'value'
            final ch = placeholderChannels.get(outName)

            if( isValue && items.size() == 1 ) {
                ch.bind(StageArchive.rebuildValue(items[0] as List<Map>, basePath.resolve('0')))
            }
            else {
                int idx = 0
                for( final elements : items ) {
                    ch.bind(StageArchive.rebuildValue(elements as List<Map>, basePath.resolve(String.valueOf(idx))))
                    idx++
                }
                ch.bind(Channel.STOP)
            }
        }
    }

    private void forwardOutputs(ChannelOut realOutput, Map<String, DataflowWriteChannel> placeholderChannels, List<String> declaredOutputs) {
        for( final outName : declaredOutputs ) {
            final srcCh = CH.getReadChannel(realOutput.getProperty(outName))
            final dstCh = placeholderChannels.get(outName)
            DataflowHelper.subscribeImpl(srcCh, [
                onNext: { Object value -> dstCh.bind(value) } as Closure,
                onComplete: { dstCh.bind(Channel.STOP) } as Closure
            ] as Map<String, Closure>)
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
