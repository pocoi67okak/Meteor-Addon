package com.example.addon.modules;

import com.example.addon.AutocropAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

public class AutoCrop extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius")
        .description("The radius to scan for crops.")
        .defaultValue(4.0)
        .min(1.0)
        .sliderMax(6.0)
        .build()
    );

    public AutoCrop() {
        super(AutocropAddon.CATEGORY, "auto-crop", "Automatically harvests and replants fully grown crops.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        BlockIterator.register((int) Math.ceil(radius.get()), (int) Math.ceil(radius.get()), (blockPos, blockState) -> {
            if (isFullyGrownCrop(blockState)) {
                harvestAndReplant(blockPos, blockState);
            }
        });
    }

    private boolean isFullyGrownCrop(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return crop.isMature(state);
        }
        return false;
    }

    private void harvestAndReplant(BlockPos pos, BlockState state) {
        Item seedItem = getSeedItem(state);
        if (seedItem == null) return;

        // Ломаем блок
        BlockUtils.breakBlock(pos, true);

        // Ищем семена в инвентаре для посадки
        FindItemResult seedResult = InvUtils.find(seedItem);
        if (seedResult.found()) {
            BlockUtils.place(pos, seedResult, true, 50, true, true);
        }
    }

    private Item getSeedItem(BlockState state) {
        if (state.isOf(Blocks.WHEAT)) return Items.WHEAT_SEEDS;
        if (state.isOf(Blocks.CARROTS)) return Items.CARROT;
        if (state.isOf(Blocks.POTATOES)) return Items.POTATO;
        if (state.isOf(Blocks.BEETROOTS)) return Items.BEETROOT_SEEDS;
        return null;
    }
}
