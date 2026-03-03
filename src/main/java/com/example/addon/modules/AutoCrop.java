package com.example.addon.modules;

import com.example.addon.AutocropAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.CocoaBlock;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class AutoCrop extends Module {
    public enum RotationMode {
        Replant,
        Harvest,
        Always,
        None
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCrops = settings.createGroup("Crops");

    // General Settings
    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius")
        .description("The radius to scan for crops.")
        .defaultValue(4.0)
        .min(1.0)
        .sliderMax(10.0)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in ticks between actions (1 = lightning fast, 20 = slow).")
        .defaultValue(5)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> replant = sgGeneral.add(new BoolSetting.Builder()
        .name("replant")
        .description("Whether to automatically replant seeds after harvesting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<RotationMode> rotationMode = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("rotations")
        .description("When to rotate your head.")
        .defaultValue(RotationMode.Always)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Send local chat messages explaining why actions fail.")
        .defaultValue(false)
        .build()
    );

    // Crops Settings
    private final Setting<Boolean> harvestWheat = sgCrops.add(new BoolSetting.Builder()
        .name("wheat")
        .description("Harvest wheat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> harvestCarrots = sgCrops.add(new BoolSetting.Builder()
        .name("carrots")
        .description("Harvest carrots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> harvestPotatoes = sgCrops.add(new BoolSetting.Builder()
        .name("potatoes")
        .description("Harvest potatoes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> harvestBeetroots = sgCrops.add(new BoolSetting.Builder()
        .name("beetroots")
        .description("Harvest beetroots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> harvestNetherWart = sgCrops.add(new BoolSetting.Builder()
        .name("nether-wart")
        .description("Harvest nether wart.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> harvestCocoa = sgCrops.add(new BoolSetting.Builder()
        .name("cocoa-beans")
        .description("Harvest cocoa beans.")
        .defaultValue(true)
        .build()
    );

    private int timer = 0;
    private boolean actionTakenThisTick = false;

    public AutoCrop() {
        super(Categories.Player, "auto-crop", "Automatically harvests and replants fully grown crops.");
    }

    @Override
    public void onActivate() {
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        actionTakenThisTick = false;

        BlockIterator.register((int) Math.ceil(radius.get()), (int) Math.ceil(radius.get()), (blockPos, blockState) -> {
            if (actionTakenThisTick) return;

            if (isFullyGrownCrop(blockState) && isCropEnabled(blockState)) {
                harvestAndReplant(blockPos, blockState);
                if (actionTakenThisTick) {
                    timer = delay.get();
                }
            }
        });
    }

    private boolean isCropEnabled(BlockState state) {
        if (state.isOf(Blocks.WHEAT)) return harvestWheat.get();
        if (state.isOf(Blocks.CARROTS)) return harvestCarrots.get();
        if (state.isOf(Blocks.POTATOES)) return harvestPotatoes.get();
        if (state.isOf(Blocks.BEETROOTS)) return harvestBeetroots.get();
        if (state.isOf(Blocks.NETHER_WART)) return harvestNetherWart.get();
        if (state.isOf(Blocks.COCOA)) return harvestCocoa.get();
        return false;
    }

    private boolean isFullyGrownCrop(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMature(state);
        }
        if (state.isOf(Blocks.NETHER_WART)) {
            return state.get(NetherWartBlock.AGE) >= 3;
        }
        if (state.isOf(Blocks.COCOA)) {
            return state.get(CocoaBlock.AGE) >= 2;
        }
        return false;
    }

    private void harvestAndReplant(BlockPos pos, BlockState state) {
        Item seedItem = getSeedItem(state);
        if (seedItem == null) return;

        // Break logic
        Runnable breakAction = () -> {
            boolean broken = BlockUtils.breakBlock(pos, true);
            if (broken) {
                if (debug.get()) info("Harvested crop at " + pos.toShortString());
                actionTakenThisTick = true;
            } else {
                if (debug.get()) info("Failed to break crop at " + pos.toShortString());
            }
        };

        if (rotationMode.get() == RotationMode.Always || rotationMode.get() == RotationMode.Harvest) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), breakAction);
        } else {
            breakAction.run();
        }

        if (!actionTakenThisTick) return; // If we didn't break it, don't replant

        // Replant logic
        if (replant.get()) {
            FindItemResult seedResult = InvUtils.find(seedItem);
            if (seedResult.found()) {
                Runnable placeAction = () -> {
                    // Slight delay is handled by the overall module timer resetting, 
                    // but block place and break in same tick without delay is risky for anti cheat.
                    // Assuming meteor's BlockUtils handles instant tick placement somewhat gracefully.
                    boolean placed = BlockUtils.place(pos, seedResult, rotationMode.get() == RotationMode.Always || rotationMode.get() == RotationMode.Replant, 50, true, true);
                    if (debug.get()) {
                        if (placed) info("Replanted seed at " + pos.toShortString());
                        else info("Failed to replant seed at " + pos.toShortString());
                    }
                };

                if (rotationMode.get() == RotationMode.Always || rotationMode.get() == RotationMode.Replant) {
                    Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), placeAction);
                } else {
                    placeAction.run();
                }
            } else {
                if (debug.get()) info("Could not replant at " + pos.toShortString() + ": No " + seedItem.getTranslationKey() + " found in inventory.");
            }
        }
    }

    private Item getSeedItem(BlockState state) {
        if (state.isOf(Blocks.WHEAT)) return Items.WHEAT_SEEDS;
        if (state.isOf(Blocks.CARROTS)) return Items.CARROT;
        if (state.isOf(Blocks.POTATOES)) return Items.POTATO;
        if (state.isOf(Blocks.BEETROOTS)) return Items.BEETROOT_SEEDS;
        if (state.isOf(Blocks.NETHER_WART)) return Items.NETHER_WART;
        if (state.isOf(Blocks.COCOA)) return Items.COCOA_BEANS;
        return null;
    }
}
