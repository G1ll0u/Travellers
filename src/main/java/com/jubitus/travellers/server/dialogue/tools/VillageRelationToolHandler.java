package com.jubitus.travellers.server.dialogue.tools;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.server.dialogue.DialoguePhrasing;
import com.jubitus.travellers.world.mill.MillRelations;
import com.jubitus.travellers.world.mill.MillVillageIndex;
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

        MillVillageIndex.VillageInfo villageA = MillVillageIndex.findVillageByName(world, nameA);
        MillVillageIndex.VillageInfo villageB = MillVillageIndex.findVillageByName(world, nameB);

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
