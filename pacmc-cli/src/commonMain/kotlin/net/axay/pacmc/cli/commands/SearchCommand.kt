package net.axay.pacmc.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.int
import net.axay.pacmc.app.repoapi.repoApiContext
import net.axay.pacmc.cli.launchJob
import net.axay.pacmc.cli.terminal
import net.axay.pacmc.cli.terminal.printProject
import net.axay.pacmc.repoapi.CachePolicy

class SearchCommand : CliktCommand(
    name = "search",
    help = "Search for mods",
) {
    private val query by argument()
    private val searchLimit by option(
        "-l", "--limit", help = "Number of results to limit search to").int().default(8)

    override fun run() = launchJob {
        terminal.println("Searching with the given query '$query'")

        val searchResults = repoApiContext(CachePolicy.ONLY_FRESH) { it.search(query, null, searchLimit) }
        terminal.println()

        if (searchResults.isEmpty()) {
            terminal.warning("Did not get any results for the given query '$query'")
            return@launchJob
        }

        searchResults.forEach(terminal::printProject)
    }
}
