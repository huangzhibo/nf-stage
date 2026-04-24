package nextflow.plugin

import java.nio.file.Files
import java.nio.file.Path

import spock.lang.Specification
import spock.lang.TempDir

class StageObserverTest extends Specification {

    @TempDir
    Path tempDir

    def 'ClonedChannel should store argIndex, original and clone' () {
        given:
        def original = new Object()
        def clone = new Object() as groovyx.gpars.dataflow.DataflowWriteChannel

        when:
        def cc = new StageObserver.ClonedChannel(2, original, clone)

        then:
        cc.argIndex == 2
        cc.original.is(original)
        cc.clone.is(clone)
    }

    // -- probeWritable --

    def 'probeWritable should return true for a writable dir and leave no residue' () {
        when:
        def ok = StageObserver.probeWritable(tempDir)

        then:
        ok
        Files.list(tempDir).count() == 0
    }

    def 'probeWritable should create archiveRoot if it does not yet exist' () {
        given:
        def archiveRoot = tempDir.resolve('nested/archive')

        when:
        def ok = StageObserver.probeWritable(archiveRoot)

        then:
        ok
        Files.isDirectory(archiveRoot)
        Files.list(archiveRoot).count() == 0
    }

    def 'probeWritable should return false when archiveRoot is not writable' () {
        given:
        def readOnly = Files.createDirectory(tempDir.resolve('ro'))
        readOnly.toFile().setWritable(false, false)

        when:
        def ok = StageObserver.probeWritable(readOnly)

        then:
        !ok

        cleanup:
        readOnly.toFile().setWritable(true, false)
    }
}
