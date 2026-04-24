package nextflow.plugin

import java.nio.file.Path

import nextflow.processor.TaskHandler
import nextflow.processor.TaskProcessor
import nextflow.processor.TaskRun
import nextflow.trace.TraceRecord
import spock.lang.Specification
import spock.lang.TempDir

class StageTaskCollectorTest extends Specification {

    @TempDir
    Path tempDir

    def setup() {
        StageTaskCollector.stageTasks.clear()
    }

    def cleanup() {
        StageTaskCollector.stageTasks.clear()
    }

    private TaskHandler mockHandler(String processName, String hashLog) {
        def processor = Mock(TaskProcessor) { getName() >> processName }
        def task = Mock(TaskRun) {
            getProcessor() >> processor
            getHashLog() >> hashLog
        }
        def handler = Mock(TaskHandler) { getTask() >> task }
        return handler
    }

    // -- onProcessComplete --

    def 'onProcessComplete should collect hashLog under the stage-name prefix' () {
        given:
        def collector = new StageTaskCollector(Mock(StageObserver))

        when:
        collector.onProcessComplete(mockHandler('ALIGN:BWA_MEM', 'ab/123456'), Mock(TraceRecord))

        then:
        StageTaskCollector.stageTasks['ALIGN'] == ['ab/123456']
    }

    def 'onProcessComplete should skip processes without a colon in the name' () {
        given:
        def collector = new StageTaskCollector(Mock(StageObserver))

        when:
        collector.onProcessComplete(mockHandler('LOOSE_PROCESS', 'ab/123456'), Mock(TraceRecord))

        then:
        StageTaskCollector.stageTasks.isEmpty()
    }

    def 'onProcessComplete should skip when hashLog is null or empty' () {
        given:
        def collector = new StageTaskCollector(Mock(StageObserver))

        when:
        collector.onProcessComplete(mockHandler('ALIGN:FOO', null), Mock(TraceRecord))
        collector.onProcessComplete(mockHandler('ALIGN:FOO', ''), Mock(TraceRecord))

        then:
        !StageTaskCollector.stageTasks.containsKey('ALIGN')
    }

    def 'onProcessComplete should aggregate multiple tasks under same stage' () {
        given:
        def collector = new StageTaskCollector(Mock(StageObserver))

        when:
        collector.onProcessComplete(mockHandler('ALIGN:A',  'ab/1'), Mock(TraceRecord))
        collector.onProcessComplete(mockHandler('ALIGN:B',  'cd/2'), Mock(TraceRecord))
        collector.onProcessComplete(mockHandler('CALL:C',   'ef/3'), Mock(TraceRecord))

        then:
        StageTaskCollector.stageTasks['ALIGN'] == ['ab/1', 'cd/2']
        StageTaskCollector.stageTasks['CALL']  == ['ef/3']
    }

    def 'onProcessComplete should only use prefix before first colon' () {
        given:
        def collector = new StageTaskCollector(Mock(StageObserver))

        when:
        collector.onProcessComplete(mockHandler('ALIGN:SUB:GRAND', 'ab/1'), Mock(TraceRecord))

        then:
        StageTaskCollector.stageTasks['ALIGN'] == ['ab/1']
    }

    // -- onFlowComplete --

    def 'onFlowComplete should patch task_hashes for each archived stage' () {
        given:
        def archive = Mock(StageArchive)
        def observer = Mock(StageObserver) {
            getArchive() >> archive
            getArchivedStages() >> [
                ALIGN: 'sha256:aaa0000000000000bbbb',
                CALL : 'sha256:ccc0000000000000dddd',
            ]
        }
        def collector = new StageTaskCollector(observer)
        StageTaskCollector.stageTasks['ALIGN'] = ['ab/1', 'cd/2']
        StageTaskCollector.stageTasks['CALL']  = ['ef/3']

        when:
        collector.onFlowComplete()

        then:
        1 * archive.patchTaskHashes('ALIGN', 'sha256:aaa0000000000000bbbb', ['ab/1', 'cd/2'])
        1 * archive.patchTaskHashes('CALL',  'sha256:ccc0000000000000dddd', ['ef/3'])
        StageTaskCollector.stageTasks.isEmpty()
    }

    def 'onFlowComplete should pass empty list when no tasks were collected for a stage' () {
        given:
        def archive = Mock(StageArchive)
        def observer = Mock(StageObserver) {
            getArchive() >> archive
            getArchivedStages() >> [ALIGN: 'sha256:aaa0000000000000bbbb']
        }
        def collector = new StageTaskCollector(observer)

        when:
        collector.onFlowComplete()

        then:
        1 * archive.patchTaskHashes('ALIGN', 'sha256:aaa0000000000000bbbb', [])
    }

    def 'onFlowComplete should no-op when archive is null' () {
        given:
        def observer = Mock(StageObserver) { getArchive() >> null }
        def collector = new StageTaskCollector(observer)
        StageTaskCollector.stageTasks['ALIGN'] = ['ab/1']

        when:
        collector.onFlowComplete()

        then:
        noExceptionThrown()
        // early return path does not clear stageTasks
        StageTaskCollector.stageTasks['ALIGN'] == ['ab/1']
    }

    def 'onFlowComplete should not fail when archivedStages is empty' () {
        given:
        def archive = Mock(StageArchive)
        def observer = Mock(StageObserver) {
            getArchive() >> archive
            getArchivedStages() >> [:]
        }
        def collector = new StageTaskCollector(observer)
        StageTaskCollector.stageTasks['ORPHAN'] = ['ab/1']

        when:
        collector.onFlowComplete()

        then:
        0 * archive.patchTaskHashes(_, _, _)
        StageTaskCollector.stageTasks.isEmpty()   // final clear still runs
    }
}
