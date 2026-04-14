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
import java.nio.file.Paths
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
 * Uses a unified clone-channel approach:
 * 1. All channel args are cloned and replaced in args[]
 *    (proceed closure captures the same array reference,
 *    so processes register with clone channels as input)
 * 2. Original channels are subscribed to collect values
 * 3. After all values arrive, digest is computed from content
 * 4. If archive matches: emit archived data, stop clones
 *    If no match: forward values to clones, processes execute
 */
@Slf4j
class StageObserver implements WorkflowInterceptor {

    /** Info about a cloned channel arg */
    static class ClonedChannel {
        final int argIndex
        final Object original
        final DataflowWriteChannel clone

        ClonedChannel(int argIndex, Object original, DataflowWriteChannel clone) {
            this.argIndex = argIndex
            this.original = original
            this.clone = clone
        }
    }

    private Session session

    private StageArchive archive0

    private Path cachedStagesTsv

    private final ConcurrentHashMap<String, String> archivedStages0 = new ConcurrentHashMap<>()

    private volatile boolean initialized

    private volatile boolean headerWritten

    StageArchive getArchive() { archive0 }

    Map<String, String> getArchivedStages() { archivedStages0 }

    private void init() {
        if( initialized ) return
        this.session = Global.session as Session
        final config = (session.config.navigate('stage') ?: Collections.emptyMap()) as Map
        final archiveRoot = (config.get('archiveRoot') ?: '.nf-stage-archive') as String
        final launchDir = Paths.get('.').toRealPath()
        this.archive0 = new StageArchive(launchDir.resolve(archiveRoot) as Path)
        final cachedStagesFile = (config.get('cachedStagesFile') ?: 'cached-stages.tsv') as String
        this.cachedStagesTsv = Path.of(cachedStagesFile)
        Files.deleteIfExists(this.cachedStagesTsv)
        this.initialized = true
        log.debug "Stage interceptor initialized, archive root: ${archiveRoot}"
    }

    @Override
    Object intercept(Object workflowObj, Object[] args, Closure proceed) {
        init()
        final workflow = (WorkflowDef) workflowObj
        final name = workflow.name

        // clone channel args; hash static args immediately
        // NOTE: args[] is modified in place so that the proceed closure
        // (which captures the same array reference) passes clones to the workflow
        final clonedChannels = new ArrayList<ClonedChannel>()
        final staticHasher = Hashing.sha256().newHasher()
        staticHasher.putUnencodedChars(name)
        final builder = new HashBuilder().withHasher(staticHasher)

        for( int i = 0; i < args.length; i++ ) {
            if( CH.isChannel(args[i]) ) {
                final clone = CH.create()
                clonedChannels.add(new ClonedChannel(i, args[i], clone))
                args[i] = clone
            }
            else {
                builder.with(args[i])
            }
        }
        final staticDigestPart = staticHasher.hash().toString()

        // proceed — processes register with clone channels as input
        final realOutput = proceed.call() as ChannelOut

        // build placeholder outputs (returned to downstream)
        final outputNames = realOutput.getNames() as List<String>
        final outputIsValue = new LinkedHashMap<String, Boolean>()
        final placeholders = new LinkedHashMap<String, DataflowWriteChannel>()
        for( final outName : outputNames ) {
            final isValue = CH.isValue(realOutput.getProperty(outName))
            outputIsValue.put(outName, isValue)
            placeholders.put(outName, CH.create(isValue))
        }

        if( clonedChannels.isEmpty() ) {
            onDigestReady(name, "sha256:${staticDigestPart}",
                         realOutput, placeholders, outputNames, outputIsValue,
                         clonedChannels, null)
        }
        else {
            subscribeAndCollect(name, staticDigestPart,
                               realOutput, placeholders, outputNames, outputIsValue,
                               clonedChannels)
        }

        return new ChannelOut(placeholders)
    }

    /**
     * Subscribe to all original channels, collect values,
     * compute digest when all complete, then decide.
     */
    private void subscribeAndCollect(String name, String staticDigestPart,
                                    ChannelOut realOutput,
                                    Map<String, DataflowWriteChannel> placeholders,
                                    List<String> outputNames,
                                    Map<String, Boolean> outputIsValue,
                                    List<ClonedChannel> clonedChannels) {
        final collected = new LinkedHashMap<Integer, List<Object>>()
        final pending = new AtomicInteger(clonedChannels.size())

        for( final cc : clonedChannels ) {
            final int capturedIndex = cc.argIndex
            collected.put(capturedIndex, Collections.synchronizedList(new ArrayList()))

            DataflowHelper.subscribeImpl(CH.getReadChannel(cc.original), [
                onNext: { Object value ->
                    collected.get(capturedIndex).add(value)
                } as Closure,
                onComplete: {
                    if( pending.decrementAndGet() == 0 ) {
                        final digest = computeContentDigest(staticDigestPart, collected)
                        onDigestReady(name, digest,
                                     realOutput, placeholders, outputNames, outputIsValue,
                                     clonedChannels, collected)
                    }
                } as Closure
            ] as Map<String, Closure>)
        }
    }

    private String computeContentDigest(String staticDigestPart, Map<Integer, List<Object>> collected) {
        final hasher = Hashing.sha256().newHasher()
        hasher.putUnencodedChars(staticDigestPart)
        for( final entry : collected.entrySet() ) {
            new HashBuilder()
                .withHasher(hasher)
                .withMode(CacheHelper.HashMode.SHA256)
                .with(entry.value)
        }
        return "sha256:${hasher.hash().toString()}"
    }

    /**
     * Digest is ready. Check archive and either restore or execute.
     */
    private void onDigestReady(String name, String digest,
                              ChannelOut realOutput,
                              Map<String, DataflowWriteChannel> placeholders,
                              List<String> outputNames,
                              Map<String, Boolean> outputIsValue,
                              List<ClonedChannel> clonedChannels,
                              Map<Integer, List<Object>> collected) {
        log.debug "Stage ${name} digest: ${digest}"

        try {
            final cached = archive0.findArchive(name, digest)
            if( cached != null ) {
                restoreFromArchive(name, cached, placeholders, clonedChannels)
            }
            else {
                executeAndArchive(name, digest, realOutput,
                                 placeholders, outputNames, outputIsValue,
                                 clonedChannels, collected)
            }
        }
        catch( Exception e ) {
            log.error "Stage ${name} archive/restore failed, falling back to normal execution: ${e.message}", e
            forwardOutputs(realOutput, placeholders, outputNames, outputIsValue)
            feedClones(clonedChannels, collected)
        }
    }

    private void restoreFromArchive(String name, Map cached,
                                   Map<String, DataflowWriteChannel> placeholders,
                                   List<ClonedChannel> clonedChannels) {
        log.info "Restoring stage ${name} from archive"
        emitArchivedData(cached, placeholders)
        for( final cc : clonedChannels ) {
            cc.clone.bind(Channel.STOP)
        }
        writeCachedStageTsv(cached)
    }

    private void executeAndArchive(String name, String digest,
                                  ChannelOut realOutput,
                                  Map<String, DataflowWriteChannel> placeholders,
                                  List<String> outputNames,
                                  Map<String, Boolean> outputIsValue,
                                  List<ClonedChannel> clonedChannels,
                                  Map<Integer, List<Object>> collected) {
        log.info "Executing stage ${name} (no archive found)"
        // single subscription: forward to placeholder AND collect for archiving
        forwardAndCollectOutputs(realOutput, placeholders, outputNames, outputIsValue, name, digest)
        feedClones(clonedChannels, collected)
        archivedStages0.put(name, digest)
    }

    private void feedClones(List<ClonedChannel> clonedChannels, Map<Integer, List<Object>> collected) {
        if( collected == null ) return
        for( final cc : clonedChannels ) {
            for( final value : collected.get(cc.argIndex) ) {
                cc.clone.bind(value)
            }
            cc.clone.bind(Channel.STOP)
        }
    }

    private void emitArchivedData(Map stageData, Map<String, DataflowWriteChannel> placeholders) {
        final stageName = stageData.get('stage') as String
        final digest = stageData.get('compatibility_digest') as String
        final basePath = archive0.archivePath(stageName, digest)
        final channelsData = stageData.get('channels') as Map<String, Map>

        for( final entry : channelsData.entrySet() ) {
            final chData = entry.value
            final items = chData.get('items') as List<List>
            final isValue = chData.get('type') == 'value'
            final ch = placeholders.get(entry.key)

            if( isValue && items.size() == 1 ) {
                ch.bind(StageArchive.rebuildValue(items[0] as List<Map>, basePath.resolve('0')))
            }
            else {
                int idx = 0
                for( final elements : items ) {
                    ch.bind(StageArchive.rebuildValue(elements as List<Map>, basePath.resolve(String.valueOf(idx))))
                    idx++
                }
                if( !isValue )
                    ch.bind(Channel.STOP)
            }
        }
    }

    /**
     * Forward realOutput to placeholders (for downstream) AND collect for archiving,
     * using a single subscription per channel to avoid double-consume on DataflowQueue.
     */
    private void forwardAndCollectOutputs(ChannelOut realOutput,
                                         Map<String, DataflowWriteChannel> placeholders,
                                         List<String> outputNames,
                                         Map<String, Boolean> outputIsValue,
                                         String stageName, String digest) {
        archive0.archiveWithForward(session, stageName, digest, realOutput, placeholders, outputIsValue)
    }

    /**
     * Forward realOutput to placeholders only (used in error fallback path).
     */
    private void forwardOutputs(ChannelOut realOutput,
                               Map<String, DataflowWriteChannel> placeholders,
                               List<String> outputNames,
                               Map<String, Boolean> outputIsValue) {
        for( final outName : outputNames ) {
            final DataflowWriteChannel capturedDst = placeholders.get(outName)
            final boolean capturedIsValue = outputIsValue.get(outName)
            final srcCh = CH.getReadChannel(realOutput.getProperty(outName))
            DataflowHelper.subscribeImpl(srcCh, [
                onNext: { Object value -> capturedDst.bind(value) } as Closure,
                onComplete: {
                    if( !capturedIsValue )
                        capturedDst.bind(Channel.STOP)
                } as Closure
            ] as Map<String, Closure>)
        }
    }

    private synchronized void writeCachedStageTsv(Map stageData) {
        final stage = stageData.get('stage') as String
        final digest = stageData.get('compatibility_digest') as String
        final taskHashes = stageData.get('task_hashes') as List ?: []
        final archivedAt = stageData.get('created_at') as String
        final archivePath = archive0.archivePath(stage, digest)

        if( !headerWritten ) {
            Files.write(cachedStagesTsv, "stage\tdigest\ttask_count\tarchive_path\tarchived_at\n".getBytes())
            headerWritten = true
        }

        final line = "${stage}\t${digest}\t${taskHashes.size()}\t${archivePath}\t${archivedAt}\n"
        Files.write(cachedStagesTsv, line.getBytes(), StandardOpenOption.APPEND)
        log.debug "Wrote cached stage entry: ${stage}"
    }
}
