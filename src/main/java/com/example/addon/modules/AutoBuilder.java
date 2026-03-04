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
        .defaultValue(50)
        .min(1)
        .sliderMax(256)
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

    public AutoBuilder() {
        super(Categories.Player, "auto-build", "Builds a square/rectangular tube shape upwards.");
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

        BlockPos origin = mc.player.getBlockPos();
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
        
        // Sort bottom to top
        queue.sort(Comparator.comparingInt(BlockPos::getY));
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        int placements = 0;

        while (!queue.isEmpty() && placements < blocksPerTick.get()) {
            BlockPos pos = queue.get(0);

            // Skip if it's already a solid block
            if (!BlockUtils.canPlace(pos)) {
                queue.remove(0);
                continue;
            }

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

            boolean placed = BlockUtils.place(pos, item, rotate.get(), 50, true);
            
            if (placed) {
                placements++;
                timer = delay.get();
                queue.remove(0);
            } else {
                // Wait for the next tick to try again (e.g., neighbor might appear if built sequentially)
                // But for now, just break loop and wait for next tick.
                break;
            }
        }

        if (queue.isEmpty() && placements == 0) {
            info("Finished building.");
            toggle();
        }
    }
}
