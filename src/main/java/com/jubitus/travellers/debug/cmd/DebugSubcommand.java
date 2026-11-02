package com.jubitus.travellers.debug.cmd;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

import java.util.Collections;
import java.util.List;

/** One `/travellersdebug <name> …` subcommand. */
public interface DebugSubcommand {

    String name();

    /** One short line for `/travellersdebug help`. */
    String description();

    /** The arguments after the name, as shown in `/travellersdebug help` and after a wrong call ("" when none). */
    String usage();

    /** Runs on the server thread. Returns false when the arguments are not recognised (the usage line is then shown). */
    boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException;

    /** Tab completions; {@code args[0]} is this subcommand's name. */
    default List<String> suggestions(ICommandSender sender, String[] args) {
        return Collections.emptyList();
    }

    /** False only for subcommands any player may run (see {@code DebugAccess.mayRun}). */
    default boolean opOnly() {
        return true;
    }
}
