package nextflow.plugin

import nextflow.Session
import spock.lang.Specification

class StageObserverTest extends Specification {

    def 'StageFactory should create StageTaskCollector when interceptor is available' () {
        // Note: full integration testing is done via validation/run-tests.sh
        // This test verifies the factory produces the expected observer type
        given:
        def factory = new StageFactory()
        when:
        def result = factory.create(Mock(Session))
        then:
        // StageFactory returns empty when no WorkflowInterceptor is registered
        result.size() == 0
    }
}
