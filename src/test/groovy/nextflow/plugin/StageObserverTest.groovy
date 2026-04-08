package nextflow.plugin

import spock.lang.Specification

class StageObserverTest extends Specification {

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
}
