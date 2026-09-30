package com.jubitus.travellers.client.dialogue;

/**
 * One line of a {@link DialogueSession}'s history — either what the player typed or what the NPC
 * answered.
 *
 * <p>A future phase (structured NPC actions — a quest offer, a map marker) would add an optional
 * action payload here, resolved by whatever handles the reply. Not built in this phase: no action
 * type exists yet, and adding an always-null placeholder field for it now would just be dead code.
 */
public final class DialogueTurn {

    public enum Role {
        PLAYER,
        NPC
    }

    public final Role role;
    public final String text;

    public DialogueTurn(Role role, String text) {
        this.role = role;
        this.text = text;
    }
}
