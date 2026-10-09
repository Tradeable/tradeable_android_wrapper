package com.tradeable.fatpack

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Merges the wrapper AAR with the full Flutter release payload
 * (flutter_release AAR incl. Dart libapp.so + flutter_assets, engine classes
 * jar, per-ABI libflutter.so jars, WebView + url_launcher plugin AARs) into
 * a single self-contained AAR, so consumers need only:
 *     implementation(files("libs/tradeable-android-wrapper-fat.aar"))
 */
object FatAar {

    private data class Input(val name: String, val file: File)
    private data class Staged(val name: String, val dir: File, val isJar: Boolean)

    /**
     * @param excludeModules "group:module" entries to leave OUT of the fat
     * AAR. Used for the slim flavor: drops libs the consumer provably
     * already has (proven by a `Duplicate class` build error), so the merge
     * can never collide with them. Their own copies satisfy both sides.
     */
    fun assemble(
        project: Project,
        wrapperAar: File,
        outFile: File,
        excludeModules: Set<String> = emptySet(),
        flavor: String = "fat"
    ) {
        require(wrapperAar.exists()) { "Wrapper AAR missing: $wrapperAar" }

        val runtimeClasspath = project.configurations.getByName("releaseRuntimeClasspath")
        val flutterArtifacts = runtimeClasspath.incoming.artifactView {
            lenient(true)
        }.artifacts.mapNotNull { result ->
            val id = result.id.componentIdentifier
            if (id is ModuleComponentIdentifier) {
                // Flutter payload groups ride inside the fat AAR. Public
                // deps (androidx.appcompat/core/kotlinx/...) stay external:
                // the consumer already provides them and re-bundling would
                // cause duplicate-class build errors. The small support libs
                // below are pulled in ONLY through Flutter plugins, so they
                // are embedded too (a consumer resolving just the fat AAR
                // would otherwise crash with NoClassDefFoundError, e.g.
                // ReLinker at FlutterLoader init).
                val keep = id.group == "com.tradeable.tradeable_flutter_sdk_module" ||
                    id.group.startsWith("io.flutter") ||
                    id.group.startsWith("dev.flutter") ||
                    (id.group.startsWith("com.getkeepsafe") && id.module == "relinker") ||
                    (id.group == "androidx.webkit" && id.module == "webkit") ||
                    (id.group == "androidx.browser" && id.module == "browser")
                // NOTE: androidx.concurrent:concurrent-futures and
                // androidx.interpolator are intentionally NOT embedded: every
                // appcompat-based app already resolves them, so bundling
                // trips checkDebugDuplicateClasses. They are only needed for
                // CustomTabs *session* features, which the SDK never uses.
                if (keep) Input("${id.group}:${id.module}", result.file) else null
            } else {
                null
            }
        }.distinctBy { it.file.absolutePath }
            .filter { artifact ->
                // artifact.name is already "group:module"
                if (artifact.name in excludeModules) {
                    project.logger.lifecycle("Fat AAR: excluding ${artifact.name} (slim flavor; consumer provides it)")
                    false
                } else {
                    true
                }
            }

        val ordered = mutableListOf<Input>()
        ordered += Input("wrapper", wrapperAar)
        ordered += flutterArtifacts.sortedWith(compareBy({ rank(it) }, { it.name }))
        project.logger.lifecycle(
            "Fat AAR inputs:\n" + ordered.joinToString("\n") { "  ${it.name}: ${it.file.name} (${it.file.length()} bytes)" }
        )

        val buildDir = project.layout.buildDirectory.get().asFile
        val stage = buildDir.resolve("fat-aar/stage").apply { deleteRecursively(); mkdirs() }
        val fat = buildDir.resolve("fat-aar/merged").apply { deleteRecursively(); mkdirs() }

        val staged = ordered.map { input ->
            val dir = stage.resolve(input.name.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { mkdirs() }
            project.copy {
                from(project.zipTree(input.file))
                into(dir)
            }
            Staged(input.name, dir, input.file.extension == "jar")
        }

        val classesStaging = fat.resolve("__classes").apply { mkdirs() }
        val jniDest = fat.resolve("jni").apply { mkdirs() }
        val assetsDest = fat.resolve("assets").apply { mkdirs() }
        val resDest = fat.resolve("res").apply { mkdirs() }
        val manifests = mutableListOf<File>()
        val proguardParts = mutableListOf<String>()
        val rTxtLines = linkedSetOf<String>()
        var duplicates = 0
        fun dup(msg: String) {
            duplicates++
            project.logger.warn("Fat AAR: $msg")
        }

        staged.forEach { (name, dir, isJar) ->
            if (isJar && !dir.resolve("AndroidManifest.xml").exists() && !dir.resolve("classes.jar").exists()) {
                // Plain jar: route by content (native .so payload vs classes).
                if (isNativeJar(dir)) {
                    dir.resolve("jni").takeIf { it.exists() }?.let {
                        copyTreeFiltered(it, jniDest) { rel -> dup("duplicate jni '$rel'") }
                    }
                    dir.resolve("lib").takeIf { it.exists() }?.listFiles()
                        ?.filter { it.isDirectory }
                        ?.forEach { abi ->
                            copyTreeFiltered(abi, jniDest.resolve(abi.name)) { rel -> dup("duplicate jni '$rel'") }
                        }
                } else {
                    copyTreeFiltered(dir, classesStaging) { rel ->
                        if (rel.startsWith("META-INF/")) return@copyTreeFiltered // drop jar manifests/signatures
                        dup("duplicate class/resource '$rel' (kept first; loser: $name)")
                    }
                }
                return@forEach
            }
            // AAR classes.jar
            dir.resolve("classes.jar").takeIf { it.exists() }?.let { jar ->
                val tmp = stage.resolve("__cls-${name.hashCode()}").apply { deleteRecursively(); mkdirs() }
                project.copy {
                    from(project.zipTree(jar))
                    into(tmp)
                }
                copyTreeFiltered(tmp, classesStaging) { rel ->
                    if (rel.startsWith("META-INF/") &&
                        (rel.endsWith(".SF") || rel.endsWith(".RSA") || rel.endsWith(".DSA"))
                    ) {
                        return@copyTreeFiltered // drop signature files
                    }
                    if (rel == "META-INF/MANIFEST.MF" || rel.endsWith("/MANIFEST.MF")) {
                        return@copyTreeFiltered
                    }
                    dup("duplicate class/resource '$rel' (kept first, from earlier input; loser: $name)")
                }
            }
            // jni/ from AARs (+ lib/<abi> remap, just in case)
            dir.resolve("jni").takeIf { it.exists() }?.let {
                copyTreeFiltered(it, jniDest) { rel -> dup("duplicate jni '$rel'") }
            }
            dir.resolve("lib").takeIf { it.exists() }?.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { abi ->
                    copyTreeFiltered(abi, jniDest.resolve(abi.name)) { rel -> dup("duplicate jni '$rel'") }
                }
            // assets/
            dir.resolve("assets").takeIf { it.exists() }?.let {
                copyTreeFiltered(it, assetsDest) { rel -> dup("duplicate asset '$rel'") }
            }
            // res/ except values (merged separately below)
            dir.resolve("res").takeIf { it.exists() }?.let { resDir ->
                resDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(resDir).path
                    if (rel.startsWith("values")) return@forEach // handled in values merge
                    val target = resDest.resolve(rel)
                    if (target.exists()) {
                        dup("duplicate res '$rel' (kept first)")
                    } else {
                        target.parentFile.mkdirs()
                        f.copyTo(target)
                    }
                }
            }
            dir.resolve("AndroidManifest.xml").takeIf { it.exists() }?.let { manifests.add(it) }
            dir.resolve("proguard.txt").takeIf { it.exists() }?.let {
                // NOTE: ProGuard comments use '#', never '//' — a '// ...'
                // separator line breaks R8 parsing in consumer release builds.
                proguardParts.add("# fat-aar merge: from $name\n" + it.readText())
            }
            dir.resolve("R.txt").takeIf { it.exists() }?.let {
                rTxtLines.addAll(it.readLines().filter { line -> line.isNotBlank() })
            }
            dir.resolve("lint.jar").takeIf { it.exists() }?.let {
                if (!fat.resolve("lint.jar").exists()) it.copyTo(fat.resolve("lint.jar"))
            }
        }

        mergeValues(project, staged, resDest)
        mergeManifests(project, manifests, fat)
        zipTreeToJar(classesStaging, fat.resolve("classes.jar"))
        classesStaging.deleteRecursively()

        if (proguardParts.isNotEmpty()) fat.resolve("proguard.txt").writeText(proguardParts.joinToString("\n"))
        if (rTxtLines.isNotEmpty()) fat.resolve("R.txt").writeText(rTxtLines.joinToString("\n"))
        writeMetadata(staged, fat)
        writeBuildInfo(project, fat, flavor, excludeModules, ordered)

        zipTreeToJar(fat, outFile)
        project.logger.lifecycle("Fat AAR written to $outFile (${outFile.length()} bytes, $duplicates duplicates skipped)")
    }

    private fun rank(a: Input) = when {
        a.name.endsWith(":flutter_release") -> 0
        a.name.contains("flutter_embedding_") -> 1
        a.name.endsWith("_release") && (a.name.contains("arm") || a.name.contains("x86")) -> 2
        else -> 3
    }

    private fun isNativeJar(dir: File): Boolean =
        dir.walkTopDown().filter { it.isFile }.any {
            val rel = it.relativeTo(dir).path
            rel.startsWith("jni/") || rel.startsWith("lib/") || rel.endsWith(".so")
        }

    private fun copyTreeFiltered(src: File, dest: File, onDuplicate: (rel: String) -> Unit) {
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(src).path
            val target = dest.resolve(rel)
            if (target.exists()) {
                onDuplicate(rel)
            } else {
                target.parentFile.mkdirs()
                f.copyTo(target)
            }
        }
    }

    private fun mergeValues(project: Project, staged: List<Staged>, resDest: File) {
        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        // Merge per resource qualifier (values, values-ru, ...): the same
        // entry name legitimately repeats across locales and must NOT
        // collapse into one file.
        data class Bucket(val doc: org.w3c.dom.Document, val root: Element, val seen: MutableSet<String>)
        val buckets = mutableMapOf<String, Bucket>()
        staged.forEach { (name, dir, _) ->
            val resDir = dir.resolve("res")
            if (!resDir.exists()) return@forEach
            resDir.walkTopDown()
                .filter { it.isFile && it.parentFile.name.startsWith("values") && it.extension == "xml" }
                .forEach { f ->
                    val qualifier = f.parentFile.name // values, values-de, ...
                    try {
                        val doc = docBuilder.parse(f)
                        val nodes = doc.documentElement.childNodes
                        for (i in 0 until nodes.length) {
                            val n = nodes.item(i)
                            if (n.nodeType != Node.ELEMENT_NODE) continue
                            val el = n as Element
                            val key = "${el.tagName}|${el.getAttribute("android:name")}|${el.getAttribute("name")}"
                            val bucket = buckets.getOrPut(qualifier) {
                                val d = docBuilder.newDocument()
                                val r = d.createElement("resources")
                                d.appendChild(r)
                                Bucket(d, r, mutableSetOf())
                            }
                            if (bucket.seen.add(key)) {
                                bucket.root.appendChild(bucket.doc.importNode(el, true))
                            } else {
                                project.logger.warn(
                                    "Fat AAR: duplicate res value '$qualifier/$key' (kept first; loser file: ${f.name} from $name)"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        project.logger.warn("Fat AAR: could not parse ${f.path}, copying raw instead: ${e.message}")
                        val target = resDest.resolve(f.relativeTo(resDir).path)
                        if (!target.exists()) {
                            target.parentFile.mkdirs()
                            f.copyTo(target)
                        }
                    }
                }
        }
        buckets.forEach { (qualifier, bucket) ->
            if (bucket.seen.isNotEmpty()) {
                val out = resDest.resolve("$qualifier/fat_merged_values.xml").apply { parentFile.mkdirs() }
                transformToFile(bucket.doc, out)
            }
        }
    }

    private fun mergeManifests(project: Project, manifests: List<File>, fat: File) {
        require(manifests.isNotEmpty()) { "No AndroidManifest.xml found in fat AAR inputs" }
        val docBuilder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        val baseDoc = docBuilder.parse(manifests.first())
        val baseRoot = baseDoc.documentElement
        val existing = manifestChildKeys(baseRoot)
        // Seed with the base (wrapper) manifest's own minSdk: the union loop
        // below only visits the other manifests.
        var maxMinSdk = baseMinSdk(baseRoot)
        // manifests.first() is the base itself: its keys are already in
        // `existing`, so only union in the rest to avoid noisy self-dupes.
        manifests.drop(1).forEach { mf ->
            val doc = docBuilder.parse(mf)
            val root = doc.documentElement
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                if (n.nodeType != Node.ELEMENT_NODE) continue
                val el = n as Element
                when (el.tagName) {
                    "uses-sdk" -> {
                        maxMinSdk = maxOf(maxMinSdk, el.getAttribute("android:minSdkVersion").toIntOrNull() ?: 0)
                    }
                    "uses-permission", "uses-permission-sdk-23", "uses-feature",
                    "queries", "permission", "permission-group" -> {
                        val key = manifestKey(el)
                        if (existing.add(key)) baseRoot.appendChild(baseDoc.importNode(el, true))
                    }
                    "application" -> mergeApplicationElement(project, baseDoc, baseRoot, el)
                    else -> project.logger.warn("Fat AAR: unhandled manifest tag '${el.tagName}' from $mf (skipped)")
                }
            }
        }
        val all = baseRoot.childNodes
        for (i in 0 until all.length) {
            val n = all.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName == "uses-sdk") {
                n.setAttribute("android:minSdkVersion", maxMinSdk.toString())
            }
        }
        project.logger.lifecycle("Fat AAR manifest minSdk=$maxMinSdk")
        transformToFile(baseDoc, fat.resolve("AndroidManifest.xml"))
    }

    private fun mergeApplicationElement(
        project: Project,
        baseDoc: org.w3c.dom.Document,
        baseRoot: Element,
        incoming: Element
    ) {
        var baseApp: Element? = null
        val all = baseRoot.childNodes
        for (j in 0 until all.length) {
            val b = all.item(j)
            if (b.nodeType == Node.ELEMENT_NODE && (b as Element).tagName == "application") {
                baseApp = b
            }
        }
        if (baseApp == null) {
            baseApp = baseDoc.createElement("application")
            baseRoot.appendChild(baseApp)
        }
        val appKeys = manifestChildKeys(baseApp!!).toMutableSet()
        val incomingChildren = incoming.childNodes
        for (j in 0 until incomingChildren.length) {
            val c = incomingChildren.item(j)
            if (c.nodeType != Node.ELEMENT_NODE) continue
            val child = c as Element
            // Drop manifest-merger directives; merging already happened.
            if (child.hasAttribute("tools:node")) child.removeAttribute("tools:node")
            val key = manifestKey(child)
            if (appKeys.add(key)) {
                baseApp!!.appendChild(baseDoc.importNode(child, true))
            } else {
                project.logger.warn("Fat AAR: duplicate manifest application entry '$key' (kept wrapper's)")
            }
        }
    }

    private fun baseMinSdk(baseRoot: Element): Int {
        val nodes = baseRoot.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName == "uses-sdk") {
                return n.getAttribute("android:minSdkVersion").toIntOrNull() ?: 0
            }
        }
        return 0
    }

    private fun manifestChildKeys(parent: Element): MutableSet<String> {        val keys = mutableSetOf<String>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) keys.add(manifestKey(n as Element))
        }
        return keys
    }

    private fun manifestKey(el: Element): String {
        val names = listOf("android:name", "name").mapNotNull {
            val v = el.getAttribute(it)
            if (v.isNotBlank()) "$it=$v" else null
        }
        return "${el.tagName}|${names.joinToString(",")}"
    }

    private fun writeMetadata(staged: List<Staged>, fat: File) {        var minCompileSdk = 0
        staged.forEach { (_, dir, _) ->
            val f = dir.resolve("META-INF/com/android/build/gradle/aar-metadata.properties")
            if (f.exists()) {
                f.readLines().forEach { line ->
                    val v = line.substringAfter("minCompileSdk=", "").trim().toIntOrNull()
                    if (v != null) minCompileSdk = maxOf(minCompileSdk, v)
                }
            }
        }
        val metaDir = fat.resolve("META-INF/com/android/build/gradle").apply { mkdirs() }
        // Consumer check (checkDebugAarMetadata) enforces this floor; the
        // Flutter artifacts require compileSdk 36.
        metaDir.resolve("aar-metadata.properties").writeText(
            "aarFormatVersion=1.0\naarMetadataVersion=1.1.0\nminCompileSdk=${maxOf(minCompileSdk, 34)}\n"
        )
    }

    /**
     * Self-identification file: any AAR can be fingerprinted without relying
     * on its filename (filenames get mixed up in manual handoffs).
     * Read with: unzip -p <aar> META-INF/tradeable-build-info.txt
     */
    private fun writeBuildInfo(
        project: Project,
        fat: File,
        flavor: String,
        excludeModules: Set<String>,
        ordered: List<Input>
    ) {
        val metaDir = fat.resolve("META-INF").apply { mkdirs() }
        val info = buildString {
            appendLine("flavor=$flavor")
            appendLine("excludes=${excludeModules.sorted().joinToString(",").ifBlank { "none" }}")
            appendLine("builtAt=${java.time.Instant.now()}")
            appendLine("inputs=")
            ordered.forEach { appendLine("  ${it.name}=${it.file.length()}") }
        }
        metaDir.resolve("tradeable-build-info.txt").writeText(info)
        project.logger.lifecycle("Fat AAR build-info: flavor=$flavor excludes=${excludeModules.sorted()}")
    }

    private fun transformToFile(doc: org.w3c.dom.Document, out: File) {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.transform(DOMSource(doc), StreamResult(out))
    }

    private fun zipTreeToJar(sourceDir: File, outJar: File) {
        ZipOutputStream(outJar.outputStream().buffered()).use { zos ->
            sourceDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val entryName = f.relativeTo(sourceDir).path.replace(File.separatorChar, '/')
                zos.putNextEntry(ZipEntry(entryName))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
