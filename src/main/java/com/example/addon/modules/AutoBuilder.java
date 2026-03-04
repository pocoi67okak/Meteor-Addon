package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AutoBuilder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> bWidth = sgGeneral.add(new IntSetting.Builder()
        .name("width")
        .description("The width of the structure (X axis).")
        .defaultValue(3)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> bLength = sgGeneral.add(new IntSetting.Builder()
        .name("length")
        .description("The length of the structure (Z axis).")
        .defaultValue(3)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> bHeight = sgGeneral.add(new IntSetting.Builder()
        .name("height")
        .description("The height of the structure.")
        .defaultValue(5)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> maxHeightLimit = sgGeneral.add(new IntSetting.Builder()
        .name("max-height-limit")
        .description("Max height limit before warning.")
        .defaultValue(300)
        .min(1)
        .sliderMax(320)
        .build()
    );

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to use for building.")
        .defaultValue(Blocks.OBSIDIAN, Blocks.COBBLESTONE)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in ticks between placements.")
        .defaultValue(1)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to place per tick.")
        .defaultValue(1)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate when placing.")
        .defaultValue(false)
        .build()
    );

    private int timer;
    private final List<BlockPos> queue = new ArrayList<>();
    private BlockPos origin;

    public AutoBuilder() {
        super(Categories.Player, "auto-build", "Builds a square/rectangular tube shape upwards and moves using Baritone.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        queue.clear();

        if (mc.player == null) return;

        if (bHeight.get() > maxHeightLimit.get()) {
            info("Too High!");
            toggle();
            return;
        }

        origin = mc.player.getBlockPos();
        int w = bWidth.get();
        int l = bLength.get();
        int h = bHeight.get();

        int startX = -(w / 2);
        int endX = startX + w - 1;
        
        int startZ = -(l / 2);
        int endZ = startZ + l - 1;

        for (int y = 0; y < h; y++) {
            for (int x = startX; x <= endX; x++) {
                for (int z = startZ; z <= endZ; z++) {
                    // Only outline (walls)
                    if (x == startX || x == endX || z == startZ || z == endZ) {
                        queue.add(origin.add(x, y, z));
                    }
                }
            }
        }
        
        // We no longer sort here since we do it dynamically per tick to avoid chaotic movements.
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        // Remove blocks that are already placed (solid blocks)
        queue.removeIf(p -> !mc.world.getBlockState(p).isReplaceable());

        if (queue.isEmpty()) {
            info("Finished building.");
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().isActive()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().onLostControl();
            }
            toggle();
            return;
        }

        int placements = 0;

        FindItemResult item = InvUtils.findInHotbar(itemStack -> {
            if (itemStack.getItem() instanceof BlockItem blockItem) {
                return blocks.get().contains(blockItem.getBlock());
            }
            return false;
        });

        if (!item.found()) {
            info("No blocks found in hotbar, stopping.");
            toggle();
            return;
        }

        while (!queue.isEmpty() && placements < blocksPerTick.get()) {
            queue.removeIf(p -> !mc.world.getBlockState(p).isReplaceable());
            if (queue.isEmpty()) break;

            // 1. Find the lowest Y level remaining in the queue
            int minY = Integer.MAX_VALUE;
            for (BlockPos p : queue) {
                if (p.getY() < minY) minY = p.getY();
            }

            // 2. Find the closest block to the player on that Y level
            BlockPos target = null;
            double minD = Double.MAX_VALUE;
            for (BlockPos p : queue) {
                if (p.getY() == minY) {
                    double d = mc.player.squaredDistanceTo(Vec3d.ofCenter(p));
                    if (d < minD) {
                        minD = d;
                        target = p;
                    }
                }
            }

            if (target == null) break;

            boolean intersects = mc.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(target));
            double dist = Math.sqrt(mc.player.squaredDistanceTo(Vec3d.ofCenter(target)));

            // If we are too far (e.g. > 4 blocks) and NOT intersecting, Baritone helps us walk near it.
            if (dist > 4.2 && !intersects) {
                if (!BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().isActive()) {
                    BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(target));
                }
                break; // Break the while loop; wait until closer next tick
            } else {
                if (BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().isActive()) {
                    BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().onLostControl();
                }
            }

            // If we are right inside the block we want to place, jump up!
            if (intersects) {
                mc.player.jump();
                break; // Give time to rise, don't place in this specific tick
            }

            // Attempt to place
            if (BlockUtils.place(target, item, rotate.get(), 50, true)) {
                queue.remove(target);
                placements++;
                timer = delay.get();
            } else {
                // Couldn't place (maybe no LOS or no supporting block at this exact angle), wait for next tick.
                // Could happen if we need to fall a bit or adjust position.
                break;
            }
        }
    }
}
