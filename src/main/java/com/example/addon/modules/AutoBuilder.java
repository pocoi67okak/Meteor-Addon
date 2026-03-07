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
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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

    private final Setting<Boolean> placeSupports = sgGeneral.add(new BoolSetting.Builder()
        .name("place-supports")
        .description("Builds support blocks underneath air if you have no floor to build on.")
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

    private int timer;
    private final List<BlockPos> blueprint = new ArrayList<>();
    private int currentLayer = 0;                 // текущий слой (Y-уровень) который строим
    private BlockPos origin;                      // начальная позиция игрока
    private BlockPos pillarBase;                  // позиция для pillar-up (центр структуры)
    private boolean needsPillarUp = false;        // нужно ли подняться

    public AutoBuilder() {
        super(Categories.Player, "auto-build", "Builds a square/rectangular tube shape upwards.");
    }

    @Override
    public void onActivate() {
        timer = 0;
        currentLayer = 0;
        needsPillarUp = false;
        blueprint.clear();

        if (mc.player == null || mc.world == null) return;

        if (bHeight.get() > maxHeightLimit.get()) {
            info("Height exceeds max limit!");
            toggle();
            return;
        }

        origin = mc.player.getBlockPos();
        pillarBase = origin; // центр для pillar-up

        int w = bWidth.get();
        int l = bLength.get();
        int h = bHeight.get();

        int startX = -(w / 2);
        int endX = startX + w - 1;
        int startZ = -(l / 2);
        int endZ = startZ + l - 1;

        // Генерируем blueprint — только стены (периметр)
        for (int y = 0; y < h; y++) {
            for (int x = startX; x <= endX; x++) {
                for (int z = startZ; z <= endZ; z++) {
                    if (x == startX || x == endX || z == startZ || z == endZ) {
                        blueprint.add(origin.add(x, y, z));
                    }
                }
            }
        }

        info("Building %d blocks (%dx%dx%d)", blueprint.size(), w, l, h);
    }

    @Override
    public void onDeactivate() {
        blueprint.clear();
        currentLayer = 0;
        needsPillarUp = false;
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

        // Собрать незаполненные блоки текущего слоя
        int targetY = origin.getY() + currentLayer;
        List<BlockPos> layerQueue = getLayerQueue(targetY);

        // Если нужны support-блоки, добавляем их
        if (placeSupports.get()) {
            List<BlockPos> supports = new ArrayList<>();
            for (BlockPos pos : layerQueue) {
                BlockPos below = pos.down();
                if (mc.world.getBlockState(below).isReplaceable() && !blueprint.contains(below)) {
                    supports.add(below);
                }
            }
            // Саппорты ставим первыми
            supports.removeIf(p -> !mc.world.getBlockState(p).isReplaceable());
            if (!supports.isEmpty()) {
                layerQueue.addAll(0, supports);
            }
        }

        // Если текущий слой завершён — переходим к следующему
        if (layerQueue.isEmpty()) {
            currentLayer++;
            if (currentLayer >= bHeight.get()) {
                info("Finished building!");
                toggle();
                return;
            }
            // Проверяем, нужно ли подняться
            needsPillarUp = true;
            if (debug.get()) info("Layer %d complete, moving to layer %d", currentLayer - 1, currentLayer);
            return;
        }

        // Если нужно подняться — pillar up
        if (needsPillarUp) {
            int newTargetY = origin.getY() + currentLayer;
            double playerY = mc.player.getY();

            // Нужно подняться до уровня, с которого дотянемся до блоков нового слоя
            // Целевая высота: блоки на newTargetY, нужно быть примерно на newTargetY - 1 или выше
            double neededY = newTargetY - 2.0;

            if (playerY < neededY) {
                doPillarUp(item);
                timer = delay.get();
                return;
            } else {
                needsPillarUp = false;
            }
        }

        // Сортируем по расстоянию до игрока (ближайшие первыми)
        Vec3d playerPos = mc.player.getPos();
        layerQueue.sort(Comparator.comparingDouble(p -> playerPos.squaredDistanceTo(Vec3d.ofCenter(p))));

        // Ставим блоки
        int placed = 0;
        for (BlockPos pos : layerQueue) {
            if (placed >= blocksPerTick.get()) break;

            double dist = Math.sqrt(mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)));
            if (dist > PLACE_RANGE) {
                // Слишком далеко — нужно pillar up чтобы подобраться
                if (debug.get()) info("Block at %s is too far (%.1f), need to get closer", pos.toShortString(), dist);

                // Попробовать подняться если блок выше
                if (pos.getY() > mc.player.getY() + 2) {
                    needsPillarUp = true;
                }
                continue;
            }

            if (!mc.world.getBlockState(pos).isReplaceable()) {
                // Уже заполнен или нужно сломать
                Block blockAt = mc.world.getBlockState(pos).getBlock();
                if (!blocks.get().contains(blockAt)) {
                    // Неправильный блок — сломать
                    if (rotate.get()) {
                        BlockPos finalPos = pos;
                        Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
                            mc.interactionManager.attackBlock(finalPos, Direction.UP);
                            mc.player.swingHand(Hand.MAIN_HAND);
                        });
                    } else {
                        mc.interactionManager.attackBlock(pos, Direction.UP);
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                    timer = delay.get();
                    return;
                }
                continue; // Блок правильный, пропускаем
            }

            // Ставим блок
            if (BlockUtils.place(pos, item, rotate.get(), 50, true)) {
                placed++;
                if (debug.get()) info("Placed block at %s", pos.toShortString());
            }
        }

        if (placed > 0) {
            timer = delay.get();
        }
    }

    // -------------------------
    // Helpers
    // -------------------------

    /**
     * Получить список незаполненных позиций blueprint на указанном Y-уровне
     */
    private List<BlockPos> getLayerQueue(int targetY) {
        List<BlockPos> queue = new ArrayList<>();
        for (BlockPos pos : blueprint) {
            if (pos.getY() == targetY && mc.world.getBlockState(pos).isReplaceable()) {
                queue.add(pos);
            }
        }
        return queue;
    }

    /**
     * Pillar up — прыжок и установка блока под ногами
     */
    private void doPillarUp(FindItemResult item) {
        BlockPos below = mc.player.getBlockPos().down();

        // Если под ногами воздух — ставим блок
        if (mc.world.getBlockState(below).isReplaceable()) {
            // Прыгаем
            if (mc.player.isOnGround()) {
                mc.player.jump();
            }
            return;
        }

        // Стоим на блоке — прыгаем и ставим блок под собой
        if (mc.player.isOnGround()) {
            mc.player.jump();
        }

        // Когда в воздухе — ставим блок прямо под ногами
        BlockPos underFeet = mc.player.getBlockPos().down();
        if (mc.world.getBlockState(underFeet).isReplaceable() && mc.player.getVelocity().y > 0) {
            BlockUtils.place(underFeet, item, rotate.get(), 50, true);
            if (debug.get()) info("Pillar up at %s", underFeet.toShortString());
        }
    }
}
