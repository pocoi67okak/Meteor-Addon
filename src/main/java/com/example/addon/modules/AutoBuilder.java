package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
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
import java.util.HashSet;
import java.util.Set;

public class AutoBuilder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> bWidth = sgGeneral.add(new IntSetting.Builder()
        .name("width")
        .description("The width of the structure (X axis).")
        .defaultValue(3)
        .min(1)
        .sliderMax(100)
        .build()
    );

    private final Setting<Integer> bLength = sgGeneral.add(new IntSetting.Builder()
        .name("length")
        .description("The length of the structure (Z axis).")
        .defaultValue(3)
        .min(1)
        .sliderMax(100)
        .build()
    );

    private final Setting<Integer> bHeight = sgGeneral.add(new IntSetting.Builder()
        .name("height")
        .description("The height of the structure.")
        .defaultValue(5)
        .min(1)
        .sliderMax(300)
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
    private final List<BlockPos> blueprint = new ArrayList<>();
    private BlockPos origin;

    public AutoBuilder() {
        super(Categories.Player, "auto-build", "Builds a square/rectangular tube shape upwards and moves using Baritone.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        blueprint.clear();

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
                        blueprint.add(origin.add(x, y, z));
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

        // Find what needs placing
        List<BlockPos> activeQueue = new ArrayList<>();
        for (BlockPos p : blueprint) {
            if (mc.world.getBlockState(p).isReplaceable()) {
                activeQueue.add(p);
            } else {
                Block blockAt = mc.world.getBlockState(p).getBlock();
                if (!blocks.get().contains(blockAt)) {
                    activeQueue.add(p); // Needs to be broken and replaced
                }
            }
        }

        if (activeQueue.isEmpty()) {
            info("Finished building.");
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().isActive()) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().onLostControl();
            }
            toggle();
            return;
        }

        int placements = 0;
        Set<BlockPos> placedThisTick = new HashSet<>();

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

        while (!activeQueue.isEmpty() && placements < blocksPerTick.get()) {
            // Remove those we just handled in this exact tick (simulate queue drain)
            activeQueue.removeIf(placedThisTick::contains);
            if (activeQueue.isEmpty()) break;

            // 1. Find the lowest Y level remaining in the queue
            int minY = Integer.MAX_VALUE;
            for (BlockPos p : activeQueue) {
                if (p.getY() < minY) minY = p.getY();
            }

            // 2. We want to place blocks sequentially along the perimeter
            BlockPos target = null;
            double minD = Double.MAX_VALUE;
            for (BlockPos p : activeQueue) {
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
            double distXY = Math.sqrt(
                Math.pow(mc.player.getX() - (target.getX() + 0.5), 2) + 
                Math.pow(mc.player.getZ() - (target.getZ() + 0.5), 2)
            );

            BlockPos walkTarget = target.up(); 
            
            if (distXY > 2.0 || mc.player.getY() < target.getY()) {
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
                mc.player.setVelocity(0, mc.player.getVelocity().y, 0); 
                break; // Give time to rise, don't place in this specific tick
            }

            if (mc.player.getY() <= target.getY() + 1.0 && !mc.player.isOnGround() && !intersects) {
                 // Might be falling into the hole
            }

            // Attempt to place or break
            boolean isReplaceable = mc.world.getBlockState(target).isReplaceable();
            if (!isReplaceable) {
                // Must be an obstruction since we filtered above
                BlockPos finalTarget = target;
                Rotations.rotate(Rotations.getYaw(finalTarget), Rotations.getPitch(finalTarget), () -> {
                    mc.interactionManager.updateBlockBreakingProgress(finalTarget, net.minecraft.util.math.Direction.UP);
                    mc.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                });
                placedThisTick.add(target);
                break; // Give time to break in this tick
            }

            if (BlockUtils.place(target, item, rotate.get(), 50, true)) {
                placedThisTick.add(target);
                placements++;
                timer = delay.get();
            } else {
                break;
            }
        }
    }
}
