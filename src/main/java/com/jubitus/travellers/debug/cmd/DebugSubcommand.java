package com.jubitus.travellers.debug.cmd;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.List;

/** One `/travellersdebug <name> …` subcommand. */
public interface DebugSubcommand {

    String name();

    /** Runs on the server thread. Returns false when the arguments are not recognised (the default culture dump then runs). */
    boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException;

    /** Tab completions; {@code args[0]} is this subcommand's name. */
    default List<String> suggestions(ICommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
