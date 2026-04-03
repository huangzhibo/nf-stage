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

    private final Path archiveRoot

    StageArchive(Path archiveRoot) {
        this.archiveRoot = archiveRoot
    }

    Map findArchive(String stageName, String digest) {
        final stageJson = archivePath(stageName, digest).resolve('stage.json')
        try {
            final data = new JsonSlurper().parse(stageJson.toFile()) as Map
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

    void archive(Session session, String stageName, String digest, ChannelOut output) {
        final names = output.getNames()
        if( !names ) return

        final collected = new LinkedHashMap<String, List<Object>>()
        final channelTypes = new LinkedHashMap<String, String>()
        final pending = new AtomicInteger(names.size())

        for( final name : names ) {
            collected.put(name, Collections.synchronizedList(new ArrayList<Object>()))
            final ch = output.getProperty(name)
            channelTypes.put(name, CH.isValue(ch) ? 'value' : 'queue')
            final readCh = CH.getReadChannel(ch)

            final events = new HashMap<String, Closure>(2)
            events.put('onNext', { Object value ->
                collected.get(name).add(value)
            } as Closure)
            events.put('onComplete', {
                if( pending.decrementAndGet() == 0 )
                    writeArchive(stageName, digest, collected, channelTypes)
            } as Closure)

            DataflowHelper.subscribeImpl(readCh, events)
        }
    }

    void patchTaskHashes(String stageName, String digest, List<String> taskHashes) {
        final stageJson = archivePath(stageName, digest).resolve('stage.json')
        if( !Files.exists(stageJson) ) return

        final data = new JsonSlurper().parse(stageJson.toFile()) as Map
        data.put('task_hashes', taskHashes)
        final json = JsonOutput.prettyPrint(JsonOutput.toJson(data))
        Files.write(stageJson, json.getBytes('UTF-8'))
        log.debug "Patched task_hashes for stage ${stageName}: ${taskHashes.size()} tasks"
    }

    Path archivePath(String stageName, String digest) {
        final raw = digest.startsWith('sha256:') ? digest.substring(7) : digest
        final prefix = raw.length() > 16 ? raw.substring(0, 16) : raw
        return archiveRoot.resolve(stageName).resolve(prefix)
    }

    // -- private --

    private void writeArchive(String stageName, String digest, Map<String, List<Object>> collected, Map<String, String> channelTypes) {
        final path = archivePath(stageName, digest)
        if( Files.exists(path.resolve('stage.json')) ) return

        Files.createDirectories(path)

        final channelsJson = new LinkedHashMap<String, Map>()
        for( final chEntry : collected.entrySet() ) {
            final name = chEntry.key
            final itemsJson = new ArrayList<List>()
            int idx = 0
            for( final value : chEntry.value ) {
                final itemDir = path.resolve(String.valueOf(idx))
                itemsJson.add(serializeValue(value, itemDir))
                idx++
            }
            channelsJson.put(name, [type: channelTypes.get(name), items: itemsJson])
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

    private static List<Map> serializeValue(Object value, Path itemDir) {
        if( value instanceof List ) {
            boolean hasFile = ((List) value).any { it instanceof Path }
            if( hasFile )
                Files.createDirectories(itemDir)
            return ((List) value).collect { el -> serializeElement(el, itemDir) }
        }
        if( value instanceof Path )
            Files.createDirectories(itemDir)
        return [serializeElement(value, itemDir)]
    }

    private static Map serializeElement(Object el, Path itemDir) {
        if( el instanceof Path ) {
            final file = (Path) el
            final fileName = file.fileName.toString()
            final target = itemDir.resolve(fileName)
            final checksum = copyWithChecksum(file, target)
            return [type: 'file', name: fileName, checksum: checksum, size: Files.size(target)]
        }
        return [type: 'value', data: el]
    }

    static Object rebuildValue(List<Map> elements, Path itemDir) {
        if( elements.size() == 1 )
            return rebuildElement(elements[0], itemDir)
        return elements.collect { rebuildElement(it, itemDir) }
    }

    private static Object rebuildElement(Map el, Path itemDir) {
        if( el.get('type') == 'file' )
            return itemDir.resolve(el.get('name') as String)
        return el.get('data')
    }

    static String copyWithChecksum(Path source, Path target) {
        final digest = MessageDigest.getInstance('SHA-256')
        source.withInputStream { raw ->
            Files.copy(new DigestInputStream(raw, digest), target, StandardCopyOption.REPLACE_EXISTING)
        }
        return "sha256:${digest.digest().encodeHex().toString()}"
    }
}
