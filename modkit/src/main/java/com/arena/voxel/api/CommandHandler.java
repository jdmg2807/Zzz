package com.arena.voxel.api;

/**
 * Handler for a mod command, in the spirit of Fabric's {@code CommandRegistrationCallback}.
 *
 * <p>Register it with {@code ctx.registerCommand("home", handler)}; the player then types
 * {@code /home} in the chat box and {@code args} holds the words after the command name.</p>
 */
public interface CommandHandler {
    /**
     * @param context the mod context (world, player, chat)
     * @param args    the arguments typed after the command, split on spaces
     * @return the message shown to the player, or null for none
     */
    String run(ModContext context, String[] args);
}
