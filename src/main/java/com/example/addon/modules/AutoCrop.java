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
import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.CocoaBlock;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

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

    private final Setting<Boolean> harvest = sgGeneral.add(new BoolSetting.Builder()
        .name("harvest")
        .description("Automatically harvest fully grown crops in range.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> replant = sgGeneral.add(new BoolSetting.Builder()
        .name("replant")
        .description("Automatically plant seeds on any empty farmland in range.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("break")
        .description("Automatically break wildflowers, short grass, and tall grass in range.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoHoe = sgGeneral.add(new BoolSetting.Builder()
        .name("hoe")
        .description("Automatically till dirt/grass blocks near water into farmland using a hoe.")
        .defaultValue(false)
        .build()
    );

    private final Setting<RotationMode> rotationMode = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("rotations")
        .description("When to rotate your head towards the target.")
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

    private final Setting<Boolean> harvestSugarCane = sgCrops.add(new BoolSetting.Builder()
        .name("sugar-cane")
        .description("Harvest sugar cane (leaves the bottom block).")
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

        BlockPos playerPos = mc.player.getBlockPos();
        int r = radius.get();

        // Priority 1: Harvest mature crops
        if (harvest.get()) {
            for (int x = -r; x <= r; x++) {
                for (int y = -r; y <= r; y++) {
                    for (int z = -r; z <= r; z++) {
                        BlockPos pos = playerPos.add(x, y, z);
                        BlockState state = mc.world.getBlockState(pos);

                        if (isFullyGrownCrop(pos, state) && isCropEnabled(state)) {
                            doHarvest(pos, state);
                            timer = delay.get();
                            return;
                        }
                    }
                }
            }
        }

        // Priority 2: Break weeds (wildflowers, short grass, tall grass)
        if (autoBreak.get()) {
            for (int x = -r; x <= r; x++) {
                for (int y = -r; y <= r; y++) {
                    for (int z = -r; z <= r; z++) {
                        BlockPos pos = playerPos.add(x, y, z);
                        BlockState state = mc.world.getBlockState(pos);

                        if (isBreakableWeed(state)) {
                            doBreakBlock(pos);
                            timer = delay.get();
                            return;
                        }
                    }
                }
            }
        }

        // Priority 3: Auto-hoe dirt/grass
        if (autoHoe.get()) {
            for (int x = -r; x <= r; x++) {
                for (int y = -r; y <= r; y++) {
                    for (int z = -r; z <= r; z++) {
                        BlockPos pos = playerPos.add(x, y, z);
                        BlockState state = mc.world.getBlockState(pos);

                        if (canTill(pos, state)) {
                            doTill(pos);
                            timer = delay.get();
                            return;
                        }
                    }
                }
            }
        }

        // Priority 4: Plant seeds on empty farmland
        if (replant.get()) {
            for (int x = -r; x <= r; x++) {
                for (int y = -r; y <= r; y++) {
                    for (int z = -r; z <= r; z++) {
                        BlockPos pos = playerPos.add(x, y, z);
                        BlockState state = mc.world.getBlockState(pos);

                        if (isEmptyFarmland(pos, state)) {
                            doPlant(pos);
                            timer = delay.get();
                            return;
                        }
                    }
                }
            }
        }
    }

    // -------------------------
    // Actions
    // -------------------------

    private void doHarvest(BlockPos pos, BlockState state) {
        boolean shouldRotate = rotationMode.get() == RotationMode.Always || rotationMode.get() == RotationMode.Harvest;

        if (shouldRotate) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
                mc.interactionManager.attackBlock(pos, Direction.UP);
            });
        } else {
            mc.interactionManager.attackBlock(pos, Direction.UP);
        }

        if (debug.get()) info("Harvested crop at " + pos.toShortString());
    }

    private void doBreakBlock(BlockPos pos) {
        boolean shouldRotate = rotationMode.get() == RotationMode.Always || rotationMode.get() == RotationMode.Harvest;

        if (shouldRotate) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
                mc.interactionManager.attackBlock(pos, Direction.UP);
            });
        } else {
            mc.interactionManager.attackBlock(pos, Direction.UP);
        }

        if (debug.get()) info("Broke weed at " + pos.toShortString());
    }

    private void doTill(BlockPos pos) {
        // Find a hoe in inventory
        FindItemResult hoeResult = InvUtils.find(stack -> stack.getItem() instanceof HoeItem);
        if (!hoeResult.found()) {
            if (debug.get()) info("No hoe found in inventory, cannot till at " + pos.toShortString());
            return;
        }

        // Swap hoe to main hand
        if (!hoeResult.isMainHand()) {
            InvUtils.swap(hoeResult.slot(), false);
        }

        boolean shouldRotate = rotationMode.get() == RotationMode.Always || rotationMode.get() != RotationMode.None;

        BlockHitResult hitResult = new BlockHitResult(
            Vec3d.ofCenter(pos),
            Direction.UP,
            pos,
            false
        );

        if (shouldRotate) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
            });
        } else {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        }

        if (debug.get()) info("Tilled dirt at " + pos.toShortString());
    }

    private void doPlant(BlockPos pos) {
        // pos is the farmland block. We need seeds in hand.
        // Find any seed we have
        FindItemResult seedResult = findAnySeed();
        if (!seedResult.found()) {
            if (debug.get()) info("No seeds found in inventory for planting at " + pos.toShortString());
            return;
        }

        if (!seedResult.isMainHand()) {
            InvUtils.swap(seedResult.slot(), false);
        }

        boolean shouldRotate = rotationMode.get() == RotationMode.Always || rotationMode.get() == RotationMode.Replant;

        BlockHitResult hitResult = new BlockHitResult(
            Vec3d.ofCenter(pos).add(0, 0.5, 0),
            Direction.UP,
            pos,
            false
        );

        if (shouldRotate) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
            });
        } else {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        }

        if (debug.get()) info("Planted seed at " + pos.toShortString());
    }

    // -------------------------
    // Checks
    // -------------------------

    private boolean isBreakableWeed(BlockState state) {
        return state.isOf(Blocks.SHORT_GRASS) ||
               state.isOf(Blocks.TALL_GRASS) ||
               state.isOf(Blocks.WILDFLOWERS);
    }

    private boolean canTill(BlockPos pos, BlockState state) {
        // Can till: dirt, grass_block, dirt_path — only if the block above is air
        if (!state.isOf(Blocks.DIRT) && !state.isOf(Blocks.GRASS_BLOCK) && !state.isOf(Blocks.DIRT_PATH)) {
            return false;
        }
        // Must have air above
        BlockState above = mc.world.getBlockState(pos.up());
        if (!above.isAir()) return false;
        // Check if hoe exists in inventory
        return InvUtils.find(stack -> stack.getItem() instanceof HoeItem).found();
    }

    private boolean isEmptyFarmland(BlockPos pos, BlockState state) {
        // Farmland with air above (no crop planted)
        if (!state.isOf(Blocks.FARMLAND)) return false;
        BlockState above = mc.world.getBlockState(pos.up());
        return above.isAir();
    }

    private FindItemResult findAnySeed() {
        return InvUtils.find(stack -> {
            Item item = stack.getItem();
            return item == Items.WHEAT_SEEDS ||
                   item == Items.CARROT ||
                   item == Items.POTATO ||
                   item == Items.BEETROOT_SEEDS ||
                   item == Items.NETHER_WART;
        });
    }

    private boolean isCropEnabled(BlockState state) {
        if (state.isOf(Blocks.WHEAT)) return harvestWheat.get();
        if (state.isOf(Blocks.CARROTS)) return harvestCarrots.get();
        if (state.isOf(Blocks.POTATOES)) return harvestPotatoes.get();
        if (state.isOf(Blocks.BEETROOTS)) return harvestBeetroots.get();
        if (state.isOf(Blocks.NETHER_WART)) return harvestNetherWart.get();
        if (state.isOf(Blocks.COCOA)) return harvestCocoa.get();
        if (state.isOf(Blocks.SUGAR_CANE)) return harvestSugarCane.get();
        return false;
    }

    private boolean isFullyGrownCrop(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMature(state);
        }
        if (state.isOf(Blocks.NETHER_WART)) {
            return state.get(NetherWartBlock.AGE) >= 3;
        }
        if (state.isOf(Blocks.COCOA)) {
            return state.get(CocoaBlock.AGE) >= 2;
        }
        if (state.isOf(Blocks.SUGAR_CANE)) {
            BlockState below = mc.world.getBlockState(pos.down());
            BlockState below2 = mc.world.getBlockState(pos.down(2));
            return below.isOf(Blocks.SUGAR_CANE) && !below2.isOf(Blocks.SUGAR_CANE);
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
