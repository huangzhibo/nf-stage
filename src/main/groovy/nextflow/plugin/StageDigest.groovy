package nextflow.plugin

import java.util.concurrent.ConcurrentHashMap

import com.google.common.hash.Hashing
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowWriteChannel
import nextflow.extension.CH
import nextflow.script.ChannelOut
import nextflow.script.WorkflowDef
import nextflow.util.HashBuilder

/**
 * Computes stage compatibility digests and maintains
 * a channel-to-digest registry for upstream digest propagation.
 */
@Slf4j
@CompileStatic
class StageDigest {

    private final ConcurrentHashMap<DataflowWriteChannel, String> registry = new ConcurrentHashMap<>()

    /**
     * Compute a compatibility digest for a named workflow invocation.
     *
     * @param workflow the workflow definition
     * @param args the arguments passed to the workflow
     * @return digest string in the form "sha256:..."
     */
    String computeDigest(WorkflowDef workflow, Object[] args) {
        final hasher = Hashing.sha256().newHasher()
        hasher.putUnencodedChars(workflow.name)

        for( final arg : args ) {
            if( CH.isChannel(arg) ) {
                // channel arg: use upstream stage digest if available
                final upstream = lookupDigest(arg)
                if( upstream != null ) {
                    hasher.putUnencodedChars(upstream)
                }
                else {
                    hasher.putInt(System.identityHashCode(arg))
                }
            }
            else {
                // static value: hash via HashBuilder
                new HashBuilder().withHasher(hasher).with(arg)
            }
        }

        final hash = hasher.hash().toString()
        return "sha256:${hash}"
    }

    /**
     * Register all channels from a ChannelOut with the given digest.
     */
    void registerChannels(ChannelOut output, String digest) {
        for( int i = 0; i < output.size(); i++ ) {
            registry.put(output.get(i), digest)
        }
    }

    /**
     * Look up the digest associated with a channel.
     */
    String lookupDigest(Object channel) {
        if( channel instanceof DataflowWriteChannel )
            return registry.get(channel)
        return null
    }
}
