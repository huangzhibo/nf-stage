package nextflow.plugin

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

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

/**
 * Handles stage archive read/write operations and stage.json management.
 */
@Slf4j
@CompileStatic
class StageArchive {

    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()

    private final Path archiveRoot

    StageArchive(Path archiveRoot) {
        this.archiveRoot = archiveRoot
    }

    /**
     * Find and read an existing valid archive.
     *
     * @return parsed stage.json as Map, or null if not found
     */
    Map findArchive(String stageName, String digest) {
        final stageJson = archivePath(stageName, digest).resolve('stage.json')
        try {
            final data = JSON_SLURPER.parse(stageJson.toFile()) as Map
            final integrity = data.get('integrity') as Map
            if( integrity?.get('status') == 'ok' )
                return data
        }
        catch( NoSuchFileException | FileNotFoundException ignored ) {
            return null
        }
        catch( Exception e ) {
            log.debug "Failed to read stage.json at ${stageJson}: ${e.message}"
        }
        return null
    }

    /**
     * Restore a ChannelOut from parsed archive data.
     */
    ChannelOut restore(Session session, Map data) {
        final stageName = data.get('stage') as String
        final digest = data.get('compatibility_digest') as String
        final basePath = archivePath(stageName, digest)
        final channelsData = data.get('channels') as Map<String, Map>
        final channels = new LinkedHashMap<String, DataflowWriteChannel>()

        for( final entry : channelsData.entrySet() ) {
            final name = entry.key
            final items = entry.value.get('items') as List<Map>
            final ch = CH.create()
            channels.put(name, ch)

            session.addIgniter {
                for( final item : items ) {
                    ch.bind(rebuildValue(basePath, item))
                }
                ch.bind(Channel.STOP)
            }
        }

        return new ChannelOut(channels)
    }

    /**
     * Archive stage output by subscribing to its channels.
     */
    void archive(Session session, String stageName, String digest, ChannelOut output) {
        final names = output.getNames()
        if( !names ) return

        final collected = new LinkedHashMap<String, List<Map>>()
        final pending = new AtomicInteger(names.size())

        for( final name : names ) {
            collected.put(name, Collections.synchronizedList(new ArrayList<Map>()))
            final readCh = CH.getReadChannel(output.getProperty(name))

            final events = new HashMap<String, Closure>(2)
            events.put('onNext', { Object value ->
                collected.get(name).add(analyzeValue(value))
            } as Closure)
            events.put('onComplete', {
                if( pending.decrementAndGet() == 0 )
                    writeArchive(stageName, digest, collected)
            } as Closure)

            DataflowHelper.subscribeImpl(readCh, events)
        }
    }

    /**
     * Patch stage.json with task hashes after all tasks have completed.
     */
    void patchTaskHashes(String stageName, String digest, List<String> taskHashes) {
        final stageJson = archivePath(stageName, digest).resolve('stage.json')
        if( !Files.exists(stageJson) ) return

        final data = JSON_SLURPER.parse(stageJson.toFile()) as Map
        data.put('task_hashes', taskHashes)
        final json = JsonOutput.prettyPrint(JsonOutput.toJson(data))
        Files.write(stageJson, json.getBytes('UTF-8'))
        log.debug "Patched task_hashes for stage ${stageName}: ${taskHashes.size()} tasks"
    }

    // -- private --

    Path archivePath(String stageName, String digest) {
        final raw = digest.startsWith('sha256:') ? digest.substring(7) : digest
        final prefix = raw.length() > 16 ? raw.substring(0, 16) : raw
        return archiveRoot.resolve(stageName).resolve(prefix)
    }

    private void writeArchive(String stageName, String digest, Map<String, List<Map>> collected) {
        final path = archivePath(stageName, digest)
        if( Files.exists(path.resolve('stage.json')) ) return

        Files.createDirectories(path)

        final channelsJson = new LinkedHashMap<String, Map>()
        for( final entry : collected.entrySet() ) {
            final itemsJson = new ArrayList<Map>()
            int idx = 0
            for( final item : entry.value ) {
                final meta = item.get('meta')
                final files = item.get('files') as List<Path>
                final itemJson = new LinkedHashMap()

                if( meta != null )
                    itemJson.put('meta', meta)
                if( files ) {
                    final itemDir = path.resolve(String.valueOf(idx))
                    Files.createDirectories(itemDir)
                    final filesJson = files.collect { Path file ->
                        final fileName = file.fileName.toString()
                        final target = itemDir.resolve(fileName)
                        final checksum = copyWithChecksum(file, target)
                        [name: fileName, checksum: checksum, size: Files.size(target)]
                    }
                    itemJson.put('files', filesJson)
                }
                itemJson.put('index', idx)
                itemsJson.add(itemJson)
                idx++
            }
            channelsJson.put(entry.key, [items: itemsJson])
        }

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

    private static Object rebuildValue(Path basePath, Map item) {
        final meta = item.get('meta')
        final filesData = item.get('files') as List<Map>
        final idx = item.get('index') as Integer
        final itemDir = idx != null ? basePath.resolve(String.valueOf(idx)) : basePath

        if( filesData && meta != null ) {
            final result = new ArrayList()
            result.add(meta)
            for( final f : filesData )
                result.add(itemDir.resolve(f.get('name') as String))
            return result
        }
        if( filesData ) {
            if( filesData.size() == 1 )
                return itemDir.resolve(filesData[0].get('name') as String)
            return filesData.collect { itemDir.resolve(it.get('name') as String) }
        }
        return meta
    }

    /**
     * Separate a channel value into meta (first non-Path) and files (Path elements).
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

    static String copyWithChecksum(Path source, Path target) {
        final digest = MessageDigest.getInstance('SHA-256')
        source.withInputStream { raw ->
            Files.copy(new DigestInputStream(raw, digest), target, StandardCopyOption.REPLACE_EXISTING)
        }
        return "sha256:${digest.digest().encodeHex().toString()}"
    }
}
