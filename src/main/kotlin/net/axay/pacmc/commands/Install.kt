package net.axay.pacmc.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextColors.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.dnq.query.eq
import kotlinx.dnq.query.first
import kotlinx.dnq.query.firstOrNull
import kotlinx.dnq.query.query
import net.axay.pacmc.data.MinecraftVersion
import net.axay.pacmc.ktorClient
import net.axay.pacmc.logging.printProject
import net.axay.pacmc.requests.CurseProxy
import net.axay.pacmc.requests.data.CurseProxyFile
import net.axay.pacmc.requests.data.CurseProxyProject
import net.axay.pacmc.requests.data.CurseProxyProjectInfo
import net.axay.pacmc.storage.Xodus
import net.axay.pacmc.storage.data.PacmcFile
import net.axay.pacmc.storage.data.XdArchive
import net.axay.pacmc.storage.data.XdMod
import net.axay.pacmc.terminal
import java.io.File
import kotlin.collections.HashMap
import kotlin.collections.List
import kotlin.collections.any
import kotlin.collections.emptyList
import kotlin.collections.filter
import kotlin.collections.filterNot
import kotlin.collections.first
import kotlin.collections.flatten
import kotlin.collections.fold
import kotlin.collections.forEach
import kotlin.collections.forEachIndexed
import kotlin.collections.isNotEmpty
import kotlin.collections.joinToString
import kotlin.collections.map
import kotlin.collections.mapNotNull
import kotlin.collections.minOrNull
import kotlin.collections.plus
import kotlin.collections.set
import kotlin.collections.setOf
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

object Install : CliktCommand(
    "Installs a minecraft mod"
) {
    private val local by option("-l", "--local").flag()
    private val archiveName by option("-a", "--archive").default(".minecraft")

    private val mod by argument()

    override fun run() = runBlocking(Dispatchers.Default) {
        val (archivePath, minecraftVersion) = Xodus.getArchiveData(archiveName) ?: return@runBlocking

        var modId: Int? = mod.toIntOrNull()
        var files: List<CurseProxyFile>? = null

        suspend fun updateFiles() {
            if (modId != null)
                files = CurseProxy.getModFiles(modId!!)
        }

        updateFiles()

        // search for the given mod if it was not a valid ID
        if (files == null || files!!.isEmpty()) {
            val searchResults = CurseProxy.search(mod, null, 9)
            modId = when {
                // ask the user which mod he wants to install
                searchResults.size > 1 -> {
                    val options = HashMap<Int, CurseProxyProject>()

                    searchResults.forEachIndexed { index, project ->
                        options[index + 1] = project

                        terminal.print(TextColors.rgb(50, 255, 236)("${index + 1}) "))
                        terminal.printProject(project, minecraftVersion.versionString, true)
                    }
                    terminal.println()

                    terminal.print("Which mod do you want to install? (${options.keys.joinToString()}) ")
                    var choice: Int? = null
                    while (choice == null) {
                        val readLine = (readLine() ?: return@runBlocking).toIntOrNull()
                        choice = when (readLine) {
                            null -> null
                            in 1..searchResults.size -> readLine
                            else -> null
                        }
                    }

                    options[choice]?.id
                }
                // just take the one matching mod
                searchResults.size == 1 -> searchResults.first().id
                else -> null
            }

            updateFiles()
        }

        fun notFoundMessage() = terminal.danger("Could not find anything for the given mod '$mod'")

        if (modId == null || files == null) {
            notFoundMessage()
            return@runBlocking
        }

        val modInfo = async { CurseProxy.getModInfo(modId) }

        val file = files?.findBestFile(minecraftVersion)?.first ?: kotlin.run {
            notFoundMessage()
            return@runBlocking
        }

        val dependenciesDeferred = async { findDependencies(file, minecraftVersion) }

        terminal.println()
        terminal.println("Installing the mod at ${gray(archivePath)}")
        terminal.println()

        downloadFile(modId, file, archivePath)
        addArchiveMod("curseforge", modId, file.id, modInfo, true)

        val dependencies = dependenciesDeferred.await()
        if (dependencies.isNotEmpty()) {
            terminal.println()
            terminal.println("Resolving dependencies...")
            terminal.println()

            dependencies.forEach {
                downloadFile(it.addonId, it.file, archivePath)
                addArchiveMod("curseforge", it.addonId, it.file.id, it.info, false)
            }
        }

        terminal.println()
        terminal.println(brightGreen("Successfully installed the given mod."))
    }

    fun List<CurseProxyFile>.findBestFile(minecraftVersion: MinecraftVersion) = this
        .filterNot { it.gameVersion.contains("Forge") && !it.gameVersion.contains("Fabric") }
        .fold<CurseProxyFile, Pair<CurseProxyFile, Int>?>(null) { acc, curseProxyFile ->
            val distance = curseProxyFile.minecraftVersions
                .mapNotNull { it.minorDistance(minecraftVersion) }
                .minOrNull()

            when {
                // this file does not have the same major version
                distance == null -> acc
                // the previous file is not fitting and this one is
                acc == null -> curseProxyFile to distance
                // prefer the file closer to the desired version
                acc.second.absoluteValue > distance.absoluteValue -> curseProxyFile to distance
                // both files are similarly close to the desired version, do additional checks
                acc.second.absoluteValue == distance.absoluteValue -> when {
                    // prefer the file for the newer version
                    acc.second > 0 && distance < 0 -> acc
                    acc.second < 0 && distance > 0 -> curseProxyFile to distance
                    // prefer the newer file
                    else -> if (acc.first.releaseDate.isAfter(curseProxyFile.releaseDate))
                        acc else curseProxyFile to distance
                }
                else -> acc
            }
        }

    class ResolvedDependency(
        val file: CurseProxyFile,
        val addonId: Int,
        val info: Deferred<CurseProxyProjectInfo>,
    )

    suspend fun findDependencies(file: CurseProxyFile, minecraftVersion: MinecraftVersion): List<ResolvedDependency> =
        coroutineScope {
            return@coroutineScope file.dependencies.map { dependency ->
                async {
                    val dependencyFile = CurseProxy.getModFiles(dependency.addonId)?.findBestFile(minecraftVersion)?.first
                        ?: return@async emptyList()
                    // save the addonId, because it is not part of the response
                    setOf(ResolvedDependency(
                        dependencyFile,
                        dependency.addonId,
                        async { CurseProxy.getModInfo(dependency.addonId) }
                    )) + findDependencies(dependencyFile, minecraftVersion).filterNot { it.addonId == dependency.addonId }
                }
            }.awaitAll().flatten()
        }

    suspend fun downloadFile(modId: Int, file: CurseProxyFile, archivePath: String) = coroutineScope {
        // download the mod file to the given archive (and display progress)
        terminal.println("Downloading " + brightCyan(file.fileName))

        val alreadyInstalled = (File(archivePath).listFiles() ?: emptyArray())
            .filter { it.name.startsWith("pacmc_") }
            .map { PacmcFile(it.name) }
            .any { it.modId == modId }

        if (alreadyInstalled) {
            terminal.println("  already installed ${green("✔")}")
            return@coroutineScope
        }

        val filename = PacmcFile("curseforge", modId.toString(), file.id.toString()).filename
        val localFile = File(archivePath, filename)

        val downloadContent = ktorClient.get<HttpResponse>(file.downloadUrl) {
            onDownload { bytesSentTotal, contentLength ->
                val progress = bytesSentTotal.toDouble() / contentLength.toDouble()
                val dashCount = (progress * 30).roundToInt()
                val percentage = (progress * 100).roundToInt()
                launch(Dispatchers.IO) {
                    val string = buildString {
                        append('[')
                        repeat(dashCount) {
                            append(green("─"))
                        }
                        append(green(">"))
                        repeat(30 - dashCount) {
                            append(' ')
                        }
                        append("] ${percentage}%")
                    }
                    terminal.print("\r  $string")
                }.join()
            }
        }.content
        terminal.println()

        downloadContent.copyAndClose(localFile.writeChannel())
    }

    suspend fun addArchiveMod(
        repository: String,
        modId: Int,
        versionId: Int,
        modInfo: Deferred<CurseProxyProjectInfo>,
        persistent: Boolean,
    ) {
        Xodus.ioTransaction {
            val archiveMods = XdArchive.query(XdArchive::name eq archiveName).first().mods
            val archiveMod = archiveMods.query(XdMod::id eq modId).firstOrNull()
            if (archiveMod != null) {
                if (persistent) archiveMod.persistent = true
                archiveMod.version = versionId
            } else {
                archiveMods.add(XdMod.new {
                    val resolvedModInfo = runBlocking { modInfo.await() }

                    this.repository = repository
                    this.id = modId

                    this.version = versionId

                    this.name = resolvedModInfo.name
                    if (resolvedModInfo.summary != null)
                        this.description = resolvedModInfo.summary

                    this.persistent = persistent
                })
            }
        }
    }
}