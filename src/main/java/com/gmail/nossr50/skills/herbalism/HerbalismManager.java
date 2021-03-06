package com.gmail.nossr50.skills.herbalism;

import com.gmail.nossr50.config.AdvancedConfig;
import com.gmail.nossr50.config.Config;
import com.gmail.nossr50.config.experience.ExperienceConfig;
import com.gmail.nossr50.config.treasure.TreasureConfig;
import com.gmail.nossr50.datatypes.mods.CustomBlock;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.*;
import com.gmail.nossr50.datatypes.treasure.HylianTreasure;
import com.gmail.nossr50.locale.LocaleLoader;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.runnables.skills.HerbalismBlockUpdaterTask;
import com.gmail.nossr50.skills.SkillManager;
import com.gmail.nossr50.util.*;
import com.gmail.nossr50.util.skills.SkillUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.List;

public class HerbalismManager extends SkillManager {
    public HerbalismManager(McMMOPlayer mcMMOPlayer) {
        super(mcMMOPlayer, SkillType.HERBALISM);
    }

    public boolean canBlockCheck() {
        return !(Config.getInstance().getHerbalismPreventAFK() && getPlayer().isInsideVehicle());
    }

    public boolean canGreenThumbBlock(BlockState blockState) {
        Player player = getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        return item.getAmount() > 0 && item.getType() == Material.WHEAT_SEEDS && BlockUtils.canMakeMossy(blockState) && Permissions.greenThumbBlock(player, blockState.getType());
    }

    public boolean canUseShroomThumb(BlockState blockState) {
        Player player = getPlayer();
        PlayerInventory inventory = player.getInventory();
        Material itemType = inventory.getItemInMainHand().getType();

        return (itemType == Material.BROWN_MUSHROOM || itemType == Material.RED_MUSHROOM) && inventory.contains(Material.BROWN_MUSHROOM, 1) && inventory.contains(Material.RED_MUSHROOM, 1) && BlockUtils.canMakeShroomy(blockState) && Permissions.secondaryAbilityEnabled(player, SecondaryAbility.SHROOM_THUMB);
    }

    public boolean canUseHylianLuck() {
        return Permissions.secondaryAbilityEnabled(getPlayer(), SecondaryAbility.HYLIAN_LUCK);
    }

    public boolean canGreenTerraBlock(BlockState blockState) {
        return mcMMOPlayer.getAbilityMode(AbilityType.GREEN_TERRA) && BlockUtils.canMakeMossy(blockState);
    }

    public boolean canActivateAbility() {
        return mcMMOPlayer.getToolPreparationMode(ToolType.HOE) && Permissions.greenTerra(getPlayer());
    }

    public boolean canGreenTerraPlant() {
        return mcMMOPlayer.getAbilityMode(AbilityType.GREEN_TERRA);
    }

    /**
     * Handle the Farmer's Diet ability
     *
     * @param rankChange The # of levels to change rank for the food
     * @param eventFoodLevel The initial change in hunger from the event
     * @return the modified change in hunger for the event
     */
    public int farmersDiet(int rankChange, int eventFoodLevel) {
        return SkillUtils.handleFoodSkills(getPlayer(), skill, eventFoodLevel, Herbalism.farmersDietRankLevel1, Herbalism.farmersDietMaxLevel, rankChange);
    }

    /**
     * Process the Green Terra ability.
     *
     * @param blockState The {@link BlockState} to check ability activation for
     * @return true if the ability was successful, false otherwise
     */
    public boolean processGreenTerra(BlockState blockState) {
        Player player = getPlayer();

        if (!Permissions.greenThumbBlock(player, blockState.getType())) {
            return false;
        }

        PlayerInventory playerInventory = player.getInventory();
        ItemStack seed = new ItemStack(Material.WHEAT_SEEDS);

        if (!playerInventory.containsAtLeast(seed, 1)) {
            player.sendMessage(LocaleLoader.getString("Herbalism.Ability.GTe.NeedMore"));
            return false;
        }

        playerInventory.removeItem(seed);
        player.updateInventory(); // Needed until replacement available

        return Herbalism.convertGreenTerraBlocks(blockState);
    }

    /**
     * @param blockState The {@link BlockState} to check ability activation for
     */
    public void herbalismBlockCheck(BlockState blockState) {
        Player player = getPlayer();
        Material material = blockState.getType();
        boolean oneBlockPlant = isOneBlockPlant(material);

        // Prevents placing and immediately breaking blocks for exp
        if (oneBlockPlant && mcMMO.getPlaceStore().isTrue(blockState)) {
            return;
        }

        if (!canBlockCheck()) {
            return;
        }

        int amount;
        int xp;
        boolean greenTerra = mcMMOPlayer.getAbilityMode(skill.getAbility());

        if (mcMMO.getModManager().isCustomHerbalismBlock(blockState)) {
            CustomBlock customBlock = mcMMO.getModManager().getBlock(blockState);
            xp = customBlock.getXpGain();

            if (Permissions.secondaryAbilityEnabled(player, SecondaryAbility.HERBALISM_DOUBLE_DROPS) && customBlock.isDoubleDropEnabled()) {
                if(checkDoubleDrop(blockState))
                    BlockUtils.markDropsAsBonus(blockState, greenTerra);
            }
        }
        else {
            xp = ExperienceConfig.getInstance().getXp(skill, blockState.getBlockData());

            if (!oneBlockPlant) {
                //Kelp is actually two blocks mixed together
                if(material == Material.KELP_PLANT || material == Material.KELP) {
                    amount = Herbalism.countAndMarkDoubleDropsKelp(blockState, greenTerra,this);
                } else {
                    amount = Herbalism.countAndMarkDoubleDropsMultiBlockPlant(blockState, greenTerra, this);
                }

                xp *= amount;
            } else {
                /* MARK SINGLE BLOCK CROP FOR DOUBLE DROP */
                if(checkDoubleDrop(blockState))
                    BlockUtils.markDropsAsBonus(blockState, greenTerra);
            }
            
            if (Permissions.greenThumbPlant(player, material)) {
                processGreenThumbPlants(blockState, greenTerra);
            }
        }

        applyXpGain(xp, XPGainReason.PVE);
    }

    private boolean isOneBlockPlant(Material material) {
        return !(material == Material.CACTUS || material == Material.CHORUS_PLANT
                || material == Material.SUGAR_CANE || material == Material.KELP_PLANT || material == Material.KELP
                || material == Material.TALL_GRASS || material == Material.TALL_SEAGRASS);
    }

    /**
     * Check for success on herbalism double drops
     * @param blockState target block state
     * @return true if double drop succeeds
     */
    public boolean checkDoubleDrop(BlockState blockState)
    {
        return BlockUtils.checkDoubleDrops(getPlayer(), blockState, skill, getSkillLevel(), SecondaryAbility.HERBALISM_DOUBLE_DROPS, activationChance);
    }

    /**
     * Process the Green Thumb ability for blocks.
     *
     * @param blockState The {@link BlockState} to check ability activation for
     * @return true if the ability was successful, false otherwise
     */
    public boolean processGreenThumbBlocks(BlockState blockState) {
        if (!SkillUtils.activationSuccessful(SecondaryAbility.GREEN_THUMB_BLOCK, getPlayer(), getSkillLevel(), activationChance)) {
            getPlayer().sendMessage(LocaleLoader.getString("Herbalism.Ability.GTh.Fail"));
            return false;
        }

        return Herbalism.convertGreenTerraBlocks(blockState);
    }

    /**
     * Process the Hylian Luck ability.
     *
     * @param blockState The {@link BlockState} to check ability activation for
     * @return true if the ability was successful, false otherwise
     */
    public boolean processHylianLuck(BlockState blockState) {
        if (!SkillUtils.activationSuccessful(SecondaryAbility.HYLIAN_LUCK, getPlayer(), getSkillLevel(), activationChance)) {
            return false;
        }

        String friendly = StringUtils.getFriendlyConfigBlockDataString(blockState.getBlockData());
        if (!TreasureConfig.getInstance().hylianMap.containsKey(friendly))
            return false;
        List<HylianTreasure> treasures = TreasureConfig.getInstance().hylianMap.get(friendly);

        Player player = getPlayer();

        if (treasures.isEmpty()) {
            return false;
        }
        int skillLevel = getSkillLevel();
        Location location = Misc.getBlockCenter(blockState);

        for (HylianTreasure treasure : treasures) {
            if (skillLevel >= treasure.getDropLevel() && SkillUtils.treasureDropSuccessful(getPlayer(), treasure.getDropChance(), activationChance)) {
                if (!EventUtils.simulateBlockBreak(blockState.getBlock(), player, false)) {
                    return false;
                }
                blockState.setType(Material.AIR);
                Misc.dropItem(location, treasure.getDrop());
                player.sendMessage(LocaleLoader.getString("Herbalism.HylianLuck"));
                return true;
            }
        }
        return false;
    }

    /**
     * Process the Shroom Thumb ability.
     *
     * @param blockState The {@link BlockState} to check ability activation for
     * @return true if the ability was successful, false otherwise
     */
    public boolean processShroomThumb(BlockState blockState) {
        Player player = getPlayer();
        PlayerInventory playerInventory = player.getInventory();
        
        if (!playerInventory.contains(Material.BROWN_MUSHROOM, 1)) {
            player.sendMessage(LocaleLoader.getString("Skills.NeedMore", StringUtils.getPrettyItemString(Material.BROWN_MUSHROOM)));
            return false;
        }

        if (!playerInventory.contains(Material.RED_MUSHROOM, 1)) {
            player.sendMessage(LocaleLoader.getString("Skills.NeedMore", StringUtils.getPrettyItemString(Material.RED_MUSHROOM)));
            return false;
        }

        playerInventory.removeItem(new ItemStack(Material.BROWN_MUSHROOM));
        playerInventory.removeItem(new ItemStack(Material.RED_MUSHROOM));
        player.updateInventory();

        if (!SkillUtils.activationSuccessful(SecondaryAbility.SHROOM_THUMB, getPlayer(), getSkillLevel(), activationChance)) {
            player.sendMessage(LocaleLoader.getString("Herbalism.Ability.ShroomThumb.Fail"));
            return false;
        }

        return Herbalism.convertShroomThumb(blockState);
    }

    /**
     * Process the Green Thumb ability for plants.
     *
     * @param blockState The {@link BlockState} to check ability activation for
     * @param greenTerra boolean to determine if greenTerra is active or not
     */
    private void processGreenThumbPlants(BlockState blockState, boolean greenTerra) {
        if (!BlockUtils.isFullyGrown(blockState) && !AdvancedConfig.getInstance().getGreenThumbUngrownCrops())
            return;

        Player player = getPlayer();
        PlayerInventory playerInventory = player.getInventory();
        Material seed = null;

        switch (blockState.getType()) {
            case CARROTS:
                seed = Material.CARROT;
                break;

            case WHEAT:
                seed = Material.WHEAT_SEEDS;
                break;

            case NETHER_WART:
                seed = Material.NETHER_WART;
                break;

            case POTATOES:
                seed = Material.POTATO;
                break;

            case BEETROOTS:
                seed = Material.BEETROOT_SEEDS;
                break;

            case COCOA:
                seed = Material.COCOA_BEANS;
                break;

            default:
                return;
        }

        ItemStack seedStack = new ItemStack(seed);

        if (!greenTerra && !SkillUtils.activationSuccessful(SecondaryAbility.GREEN_THUMB_PLANT, getPlayer(), getSkillLevel(), activationChance)) {
            return;
        }

        if (!handleBlockState(blockState, greenTerra)) {
            return;
        }

        if(!ItemUtils.isHoe(getPlayer().getInventory().getItemInMainHand()))
        {
            if (!playerInventory.containsAtLeast(seedStack, 1)) {
                return;
            }

            playerInventory.removeItem(seedStack);
            player.updateInventory(); // Needed until replacement available
        }

        new HerbalismBlockUpdaterTask(blockState).runTaskLater(mcMMO.p, 0);
    }

    private boolean handleBlockState(BlockState blockState, boolean greenTerra) {
        byte greenThumbStage = getGreenThumbStage();

        blockState.setMetadata(mcMMO.greenThumbDataKey, new FixedMetadataValue(mcMMO.p, (int) (System.currentTimeMillis() / Misc.TIME_CONVERSION_FACTOR)));
        Ageable crops = (Ageable) blockState.getBlockData();

        switch (blockState.getType()) {

            case POTATOES:
            case CARROTS:
            case WHEAT:

                if (greenTerra) {
                    crops.setAge(3);
                }
                else {
                    crops.setAge(greenThumbStage);
                }
                break;

            case BEETROOTS:
            case NETHER_WART:

                if (greenTerra || greenThumbStage > 2) {
                    crops.setAge(2);
                }
                else if (greenThumbStage == 2) {
                    crops.setAge(1);
                }
                else {
                    crops.setAge(0);
                }
               break;

            case COCOA:

                if (greenTerra || getGreenThumbStage() > 1) {
                    crops.setAge(1);
                }
                else {
                    crops.setAge(0);
                }
                break;

            default:
                return false;
        }
        blockState.setBlockData(crops);
        return true;
    }

    private byte getGreenThumbStage() {
        return (byte) Math.min(Math.min(getSkillLevel(), Herbalism.greenThumbStageMaxLevel) / Herbalism.greenThumbStageChangeLevel, 4);
    }
}
