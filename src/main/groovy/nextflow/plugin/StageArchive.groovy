package nextflow.plugin

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

import com.google.common.hash.Funnels
import com.google.common.hash.Hashing
import com.google.common.io.ByteStreams
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowWriteChannel
import nextflow.Channel
import nextflow.Session
import nextflow.extension.CH
import nextflow.extension.DataflowHelper
import nextflow.script.ChannelOut
import nextflow.script.WorkflowDef

/**
 * Handles stage archive read/write operations and stage.json management.
 */
@Slf4j
@CompileStatic
class StageArchive {

    private final Path archiveRoot

    StageArchive(Path archiveRoot) {
        this.archiveRoot = archiveRoot
    }

    /**
     * Compute the archive directory path for a stage + digest.
     */
    Path archivePath(String stageName, String digest) {
        final prefix = digestPrefix(digest)
        return archiveRoot.resolve(stageName).resolve(prefix)
    }

    /**
     * Find an existing valid archive for the given stage and digest.
     *
     * @return the archive directory path, or null if not found
     */
    Path findArchive(String stageName, String digest) {
        final path = archivePath(stageName, digest)
        final stageJson = path.resolve('stage.json')
        if( !Files.exists(stageJson) )
            return null

        try {
            final data = new JsonSlurper().parse(stageJson.toFile()) as Map
            final integrity = data.get('integrity') as Map
            if( integrity?.get('status') == 'ok' )
                return path
        }
        catch( Exception e ) {
            log.warn "Failed to read stage.json at ${stageJson}: ${e.message}"
        }
        return null
    }

    /**
     * Restore a ChannelOut from an archived stage.
     */
    ChannelOut restore(Session session, Path archivePath, WorkflowDef workflow) {
        final stageJson = archivePath.resolve('stage.json')
        final data = new JsonSlurper().parse(stageJson.toFile()) as Map
        final channelsData = data.get('channels') as Map<String, Map>
        final channels = new LinkedHashMap<String, DataflowWriteChannel>()

        for( final entry : channelsData.entrySet() ) {
            final name = entry.key
            final channelData = entry.value
            final items = channelData.get('items') as List<Map>
            final ch = CH.create()
            channels.put(name, ch)

            // emit values after DAG setup
            session.addIgniter {
                for( final item : items ) {
                    final value = rebuildValue(archivePath, item)
                    ch.bind(value)
                }
                ch.bind(Channel.STOP)
            }
        }

        return new ChannelOut(channels)
    }

    /**
     * Archive the output of a completed stage by subscribing to its channels.
     */
    void archive(Session session, String stageName, String digest, ChannelOut output, Closure onComplete) {
        final names = output.getNames()
        if( !names ) {
            log.debug "Stage ${stageName} has no named outputs, skipping archive"
            return
        }

        final collected = Collections.synchronizedMap(new LinkedHashMap<String, List<Map>>())
        final pending = new AtomicInteger(names.size())

        for( final name : names ) {
            collected.put(name, Collections.synchronizedList(new ArrayList<Map>()))
            final readCh = CH.getReadChannel(output.getProperty(name))

            final events = new HashMap<String, Closure>(2)
            events.put('onNext', { Object value ->
                final analyzed = analyzeValue(value)
                collected.get(name).add(analyzed)
            } as Closure)
            events.put('onComplete', {
                if( pending.decrementAndGet() == 0 ) {
                    writeArchive(stageName, digest, collected)
                    if( onComplete != null )
                        onComplete.call()
                }
            } as Closure)

            DataflowHelper.subscribeImpl(readCh, events)
        }
    }

    // -- private helpers --

    /**
     * Write the archive directory: copy files + write stage.json.
     */
    private void writeArchive(String stageName, String digest, Map<String, List<Map>> collected) {
        final path = archivePath(stageName, digest)

        // skip if already archived (immutable)
        if( Files.exists(path.resolve('stage.json')) ) {
            log.debug "Archive already exists at ${path}, skipping"
            return
        }

        Files.createDirectories(path)

        // process each channel's items: copy files, compute checksums
        final channelsJson = new LinkedHashMap<String, Map>()
        for( final entry : collected.entrySet() ) {
            final name = entry.key
            final items = entry.value
            final itemsJson = new ArrayList<Map>()

            for( final item : items ) {
                final meta = item.get('meta')
                final files = item.get('files') as List<Path>
                final filesJson = new ArrayList<Map>()

                if( files ) {
                    for( final file : files ) {
                        final fileName = file.fileName.toString()
                        final target = path.resolve(fileName)
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
                        final checksum = computeChecksum(target)
                        final size = Files.size(target)
                        filesJson.add([name: fileName, checksum: checksum, size: size])
                    }
                }

                final itemJson = new LinkedHashMap()
                if( meta != null )
                    itemJson.put('meta', meta)
                if( filesJson )
                    itemJson.put('files', filesJson)
                itemsJson.add(itemJson)
            }

            channelsJson.put(name, [items: itemsJson])
        }

        // write stage.json
        final stageData = [
            schema_version: 'v1',
            stage: stageName,
            compatibility_digest: digest,
            created_at: OffsetDateTime.now().toString(),
            integrity: [status: 'ok'],
            channels: channelsJson
        ]

        final json = JsonOutput.prettyPrint(JsonOutput.toJson(stageData))
        Files.write(path.resolve('stage.json'), json.getBytes('UTF-8'))
        log.info "Stage ${stageName} archived to ${path}"
    }

    /**
     * Rebuild a channel value from archived JSON item.
     */
    private Object rebuildValue(Path archivePath, Map item) {
        final meta = item.get('meta')
        final filesData = item.get('files') as List<Map>

        if( filesData && meta != null ) {
            // tuple: [meta, file1, file2, ...]
            final result = new ArrayList()
            result.add(meta)
            for( final f : filesData ) {
                result.add(archivePath.resolve(f.get('name') as String))
            }
            return result
        }
        else if( filesData ) {
            // single file or list of files
            if( filesData.size() == 1 )
                return archivePath.resolve(filesData[0].get('name') as String)
            return filesData.collect { archivePath.resolve(it.get('name') as String) }
        }
        else if( meta != null ) {
            return meta
        }
        return null
    }

    /**
     * Analyze a channel value into meta (non-Path) and files (Path) components.
     */
    static Map analyzeValue(Object value) {
        if( value instanceof List ) {
            final files = new ArrayList<Path>()
            Object meta = null
            for( final el : (List) value ) {
                if( el instanceof Path )
                    files.add((Path) el)
                else if( meta == null )
                    meta = el
            }
            return [meta: meta, files: files]
        }
        if( value instanceof Path )
            return [meta: null, files: [value]]
        return [meta: value, files: null]
    }

    /**
     * Compute SHA-256 checksum for a file.
     */
    static String computeChecksum(Path file) {
        final hasher = Hashing.sha256().newHasher()
        final input = Files.newInputStream(file)
        try {
            ByteStreams.copy(input, Funnels.asOutputStream(hasher))
        }
        finally {
            input.close()
        }
        return "sha256:${hasher.hash().toString()}"
    }

    /**
     * Extract the first 16 chars from a digest string for directory naming.
     */
    static String digestPrefix(String digest) {
        final raw = digest.startsWith('sha256:') ? digest.substring(7) : digest
        return raw.length() > 16 ? raw.substring(0, 16) : raw
    }
}
