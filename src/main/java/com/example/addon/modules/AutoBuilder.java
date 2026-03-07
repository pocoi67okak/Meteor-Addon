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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

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
        .description("Delay in ticks between actions.")
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
        .description("Rotate when placing or breaking.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> placeSupports = sgGeneral.add(new BoolSetting.Builder()
        .name("place-supports")
        .description("Builds support blocks underneath if there is no floor.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Send local debug messages to chat.")
        .defaultValue(false)
        .build()
    );

    private static final double PLACE_RANGE = 4.5;
    private static final double WALK_SPEED = 0.18;

    private int timer;
    private final List<BlockPos> blueprint = new ArrayList<>();
    private BlockPos origin;
    private BlockPos currentTarget = null;

    public AutoBuilder() {
        super(Categories.Player, "auto-build", "Builds a rectangular tube. Walks to blocks, breaks obstacles, and places automatically.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        currentTarget = null;
        blueprint.clear();

        if (mc.player == null || mc.world == null) return;

        if (bHeight.get() > maxHeightLimit.get()) {
            info("Height exceeds max limit!");
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

        // Blueprint — стены (периметр), послойно снизу вверх
        for (int y = 0; y < h; y++) {
            for (int x = startX; x <= endX; x++) {
                for (int z = startZ; z <= endZ; z++) {
                    if (x == startX || x == endX || z == startZ || z == endZ) {
                        blueprint.add(origin.add(x, y, z));
                    }
                }
            }
        }

        info("Building %d blocks (%dx%dx%d). Auto-walking enabled.", blueprint.size(), w, l, h);
    }

    @Override
    public void onDeactivate() {
        blueprint.clear();
        currentTarget = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        // Найти блок в хотбаре
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

        // Найти следующую цель (незаполненный блок)
        currentTarget = findNextTarget();

        if (currentTarget == null) {
            info("Finished building!");
            toggle();
            return;
        }

        Vec3d eyePos = mc.player.getEyePos();
        double distToTarget = Math.sqrt(eyePos.squaredDistanceTo(Vec3d.ofCenter(currentTarget)));

        // Если в пределах досягаемости — работаем
        if (distToTarget <= PLACE_RANGE) {
            doWork(currentTarget, item);
            return;
        }

        // Далеко — идём к цели
        walkToward(currentTarget);

        // Ломаем блоки на пути
        breakObstaclesInPath(currentTarget);
    }

    // -------------------------
    // Навигация
    // -------------------------

    /**
     * Идти в сторону цели (простая прямая навигация)
     */
    private void walkToward(BlockPos target) {
        Vec3d playerPos = mc.player.getPos();
        Vec3d targetVec = Vec3d.ofCenter(target);

        double dx = targetVec.x - playerPos.x;
        double dz = targetVec.z - playerPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDist < 0.3) return; // Уже на месте по горизонтали

        // Нормализуем направление и задаём скорость
        double motionX = (dx / horizontalDist) * WALK_SPEED;
        double motionZ = (dz / horizontalDist) * WALK_SPEED;

        // Сохраняем вертикальную скорость (гравитация/прыжок)
        double motionY = mc.player.getVelocity().y;

        mc.player.setVelocity(motionX, motionY, motionZ);

        // Нужно прыгнуть?
        BlockPos feetPos = mc.player.getBlockPos();
        BlockPos inFront = getBlockInFront(feetPos, dx, dz);

        // Прыгаем если перед нами твёрдый блок и мы стоим на земле
        if (mc.player.isOnGround()) {
            BlockState frontState = mc.world.getBlockState(inFront);
            if (!frontState.isAir() && !frontState.isReplaceable()) {
                mc.player.jump();
            }
            // Или если цель выше нас
            if (target.getY() > feetPos.getY() && horizontalDist < 2.0) {
                mc.player.jump();
            }
        }
    }

    /**
     * Получить позицию блока перед игроком по направлению движения
     */
    private BlockPos getBlockInFront(BlockPos feetPos, double dx, double dz) {
        int frontX = dx > 0.3 ? 1 : (dx < -0.3 ? -1 : 0);
        int frontZ = dz > 0.3 ? 1 : (dz < -0.3 ? -1 : 0);
        return feetPos.add(frontX, 0, frontZ);
    }

    /**
     * Ломать блоки, мешающие пройти к цели
     */
    private void breakObstaclesInPath(BlockPos target) {
        Vec3d playerPos = mc.player.getPos();
        double dx = Vec3d.ofCenter(target).x - playerPos.x;
        double dz = Vec3d.ofCenter(target).z - playerPos.z;

        BlockPos feetPos = mc.player.getBlockPos();
        BlockPos inFront = getBlockInFront(feetPos, dx, dz);
        BlockPos headLevel = inFront.up();

        // Ломаем блоки на уровне ног и головы перед игроком
        Vec3d eyePos = mc.player.getEyePos();

        if (isBreakableObstacle(inFront) && Math.sqrt(eyePos.squaredDistanceTo(Vec3d.ofCenter(inFront))) <= PLACE_RANGE) {
            doBreak(inFront);
            timer = delay.get();
            return;
        }

        if (isBreakableObstacle(headLevel) && Math.sqrt(eyePos.squaredDistanceTo(Vec3d.ofCenter(headLevel))) <= PLACE_RANGE) {
            doBreak(headLevel);
            timer = delay.get();
        }
    }

    // -------------------------
    // Действия
    // -------------------------

    /**
     * Выполнить работу по позиции: сломать неправильный блок или поставить нужный
     */
    private void doWork(BlockPos pos, FindItemResult item) {
        BlockState state = mc.world.getBlockState(pos);

        // Неправильный блок — ломаем
        if (!state.isReplaceable() && !isCorrectBlock(pos)) {
            doBreak(pos);
            timer = delay.get();
            return;
        }

        // Нужно поставить
        if (state.isReplaceable()) {
            int placed = 0;

            // Support-блоки сначала
            if (placeSupports.get()) {
                BlockPos below = pos.down();
                Vec3d eyePos = mc.player.getEyePos();
                if (mc.world.getBlockState(below).isReplaceable()
                    && !blueprint.contains(below)
                    && Math.sqrt(eyePos.squaredDistanceTo(Vec3d.ofCenter(below))) <= PLACE_RANGE) {
                    if (BlockUtils.place(below, item, rotate.get(), 50, true)) {
                        placed++;
                        if (debug.get()) info("Support at %s", below.toShortString());
                    }
                }
            }

            // Основной блок
            if (placed < blocksPerTick.get()) {
                if (BlockUtils.place(pos, item, rotate.get(), 50, true)) {
                    placed++;
                    if (debug.get()) info("Placed at %s", pos.toShortString());
                }
            }

            if (placed > 0) timer = delay.get();
        }
    }

    /**
     * Сломать блок
     */
    private void doBreak(BlockPos pos) {
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
                mc.interactionManager.attackBlock(pos, Direction.UP);
                mc.player.swingHand(Hand.MAIN_HAND);
            });
        } else {
            mc.interactionManager.attackBlock(pos, Direction.UP);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        if (debug.get()) info("Breaking at %s", pos.toShortString());
    }

    // -------------------------
    // Helpers
    // -------------------------

    /**
     * Найти следующий блок для работы (послойно, ближайший к игроку)
     */
    private BlockPos findNextTarget() {
        Vec3d playerPos = mc.player.getPos();

        // Ищем послойно снизу вверх
        int minY = Integer.MAX_VALUE;
        for (BlockPos pos : blueprint) {
            if (needsAction(pos) && pos.getY() < minY) {
                minY = pos.getY();
            }
        }

        if (minY == Integer.MAX_VALUE) return null;

        // На найденном слое — берём ближайший
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (BlockPos pos : blueprint) {
            if (pos.getY() == minY && needsAction(pos)) {
                double dist = playerPos.squaredDistanceTo(Vec3d.ofCenter(pos));
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = pos;
                }
            }
        }

        return closest;
    }

    private boolean needsAction(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isReplaceable()) return true;
        return !isCorrectBlock(pos);
    }

    private boolean isCorrectBlock(BlockPos pos) {
        Block blockAt = mc.world.getBlockState(pos).getBlock();
        return blocks.get().contains(blockAt);
    }

    private boolean isBreakableObstacle(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return !state.isAir() && !state.isReplaceable() && state.getHardness(mc.world, pos) >= 0;
    }
}
