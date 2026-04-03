package nextflow.plugin

import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.trace.TraceObserver
import nextflow.trace.TraceObserverFactory

/**
 * Creates {@link StageTaskCollector} to collect task hashes per stage.
 */
@CompileStatic
class StageFactory implements TraceObserverFactory {

    @Override
    Collection<TraceObserver> create(Session session) {
        final observer = Plugins.getExtension(WorkflowInterceptor) as StageObserver
        if( observer == null )
            return Collections.emptyList()
        return List.<TraceObserver>of(new StageTaskCollector(observer))
    }
}
