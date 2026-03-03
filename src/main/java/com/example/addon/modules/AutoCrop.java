package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.CocoaBlock;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class AutoCrop extends Module {
    public enum RotationMode {
        Replant,
        Harvest,
        Always,
        None
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCrops = settings.createGroup("Crops");

    // --- General ---

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("The radius to scan for crops.")
        .defaultValue(4)
        .range(1, 10)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in ticks between actions (1 = fast, 20 = slow).")
        .defaultValue(5)
        .range(1, 20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> replant = sgGeneral.add(new BoolSetting.Builder()
        .name("replant")
        .description("Replant seeds after harvesting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<RotationMode> rotationMode = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("rotations")
        .description("When to rotate your head towards the crop.")
        .defaultValue(RotationMode.Always)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Send local debug messages to chat.")
        .defaultValue(false)
        .build()
    );

    // --- Crops ---

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

    public AutoCrop() {
        super(Categories.Player, "auto-crop", "Automatically harvests and replants fully grown crops.");
    }

    @Override
    public void onActivate() {
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        // Scan for mature crops in range
        BlockPos playerPos = mc.player.getBlockPos();
        int r = radius.get();
        BlockPos target = null;
        BlockState targetState = null;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);

                    if (isFullyGrownCrop(state) && isCropEnabled(state)) {
                        target = pos;
                        targetState = state;
                        break;
                    }
                }
                if (target != null) break;
            }
            if (target != null) break;
        }

        if (target == null) return;

        // Harvest
        Item seedItem = getSeedItem(targetState);
        BlockPos finalTarget = target;

        boolean shouldRotateHarvest = rotationMode.get() == RotationMode.Always || rotationMode.get() == RotationMode.Harvest;

        if (shouldRotateHarvest) {
            Rotations.rotate(Rotations.getYaw(finalTarget), Rotations.getPitch(finalTarget), () -> {
                breakCrop(finalTarget);
            });
        } else {
            breakCrop(finalTarget);
        }

        if (debug.get()) info("Harvested crop at " + finalTarget.toShortString());

        // Replant
        if (replant.get() && seedItem != null) {
            FindItemResult seedResult = InvUtils.find(seedItem);
            if (seedResult.found()) {
                // Swap to seeds if needed
                if (!seedResult.isMainHand() && !seedResult.isOffhand()) {
                    InvUtils.swap(seedResult.slot(), false);
                }

                boolean shouldRotateReplant = rotationMode.get() == RotationMode.Always || rotationMode.get() == RotationMode.Replant;

                if (shouldRotateReplant) {
                    Rotations.rotate(Rotations.getYaw(finalTarget), Rotations.getPitch(finalTarget), () -> {
                        placeSeed(finalTarget);
                    });
                } else {
                    placeSeed(finalTarget);
                }

                if (debug.get()) info("Replanted at " + finalTarget.toShortString());
            } else {
                if (debug.get()) info("No seeds found for replanting at " + finalTarget.toShortString());
            }
        }

        timer = delay.get();
    }

    private void breakCrop(BlockPos pos) {
        mc.interactionManager.attackBlock(pos, Direction.UP);
    }

    private void placeSeed(BlockPos pos) {
        BlockHitResult hitResult = new BlockHitResult(
            Vec3d.ofCenter(pos),
            Direction.UP,
            pos.down(),
            false
        );
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
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
