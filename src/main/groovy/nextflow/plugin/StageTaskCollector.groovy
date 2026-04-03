package nextflow.plugin

import java.util.concurrent.ConcurrentHashMap

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.trace.TraceObserver
import nextflow.trace.TraceRecord

/**
 * Collects task hashes grouped by stage name.
 * Stage name is derived from the process name prefix (e.g. "ALIGN:BWA_MEM" -> "ALIGN").
 *
 * On flow completion, patches stage.json files with collected task hashes.
 */
@Slf4j
@CompileStatic
class StageTaskCollector implements TraceObserver {

    static final ConcurrentHashMap<String, List<String>> stageTasks = new ConcurrentHashMap<>()

    private StageObserver stageObserver

    StageTaskCollector(StageObserver stageObserver) {
        this.stageObserver = stageObserver
    }

    @Override
    void onProcessComplete(TaskHandler handler, TraceRecord trace) {
        final task = handler.task
        final processName = task.processor.name
        final sep = processName.indexOf(':')
        if( sep < 0 ) return

        final stageName = processName.substring(0, sep)
        final hashLog = task.getHashLog()
        if( hashLog ) {
            stageTasks.computeIfAbsent(stageName, { Collections.synchronizedList(new ArrayList<String>()) })
                .add(hashLog)
        }
    }

    @Override
    void onFlowComplete() {
        final archive = stageObserver.getArchive()
        if( archive == null ) return

        for( final entry : stageObserver.getArchivedStages().entrySet() ) {
            final stageName = entry.key
            final digest = entry.value
            final taskHashes = stageTasks.remove(stageName) ?: []
            archive.patchTaskHashes(stageName, digest, taskHashes)
        }

        stageTasks.clear()
    }
}
