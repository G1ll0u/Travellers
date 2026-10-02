package com.jubitus.travellers.dialogue;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;

/**
 * Currently equipped gear, for both sides of the conversation (E4 of the context-expansion plan).
 * {@link EntityEquipmentSlot#values()} already covers both hands and armor, so no separate held-item
 * read is needed on top of it. Each piece is described (name + condition wording) by the single
 * shared {@link DialoguePhrasing#describeEquipmentPiece} helper — never reimplemented per side.
 *
 * <p><b>Trap:</b> a ghost item ({@code TravellerEquipmentManager}'s cosmetic display-only copies used
 * during virtual battles / equipment exchange visuals - see {@code entity.equipment.isGhost}) is not
 * real gear and must never be described to the model as if it were; that's exactly the kind of
 * made-up-sounding fact D-8's grounding rule exists to prevent. This only applies to the traveller's
 * own equipment — a player's own gear is never a ghost item, so the player loop skips that check.
 */
public final class EquipmentDialogueContextProvider implements DialogueContextProvider {

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            ItemStack stack = traveller.getItemStackFromSlot(slot);
            if (stack.isEmpty() || traveller.equipment.isGhost(stack)) continue;
            builder.addEquipmentDescription(DialoguePhrasing.describeEquipmentPiece(stack));
        }

        if (player == null) return;
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            ItemStack stack = player.getItemStackFromSlot(slot);
            if (stack.isEmpty()) continue;
            builder.addPlayerEquipmentDescription(DialoguePhrasing.describeEquipmentPiece(stack));
        }
    }
}
