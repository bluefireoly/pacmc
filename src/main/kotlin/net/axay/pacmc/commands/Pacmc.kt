package net.axay.pacmc.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

object Pacmc : CliktCommand() {
    init {
        subcommands(Install, Search, Archive, Init)
    }

    override fun run() = Unit
}
