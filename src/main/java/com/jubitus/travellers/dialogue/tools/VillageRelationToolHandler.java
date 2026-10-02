package com.jubitus.travellers.dialogue.tools;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.dialogue.DialoguePhrasing;
import com.jubitus.travellers.village.MillRelations;
import com.jubitus.travellers.village.MillSettlements;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

/**
 * The {@code village_relation} dialogue tool (see {@code docs/npc-dialogue-tools-memory-plan.md} Part
 * H): "is village A at war with village B", for two settlements named by the player, not just the
 * traveller's own home village (which {@code AmbientActivityDialogueContextProvider} already covers
 * for free in the always-injected context).
 */
public final class VillageRelationToolHandler implements DialogueToolHandler {

    @Override
    public String handle(EntityTraveller traveller, EntityPlayer player, NBTTagCompound args) {
        World world = traveller.world;
        String nameA = args.getString("villageA");
        String nameB = args.getString("villageB");

        MillSettlements.Settlement villageA = MillSettlements.villageByName(world, nameA);
        MillSettlements.Settlement villageB = MillSettlements.villageByName(world, nameB);

        if (villageA == null) return "You have never heard of a place called " + safeName(nameA) + ".";
        if (villageB == null) return "You have never heard of a place called " + safeName(nameB) + ".";
        if (villageA.center.equals(villageB.center)) return "That is the same place.";

        Integer relation = MillRelations.getRelation(world, villageA.center, villageB.center, world.getTotalWorldTime());
        String relationWord = DialoguePhrasing.relationTier(relation);
        return villageA.name + " and " + villageB.name + " are on " + relationWord + " terms.";
    }

    private static String safeName(String name) {
        return name == null || name.trim().isEmpty() ? "that place" : name.trim();
    }
}
