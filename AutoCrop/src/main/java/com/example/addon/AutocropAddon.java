package com.example.addon;

import com.example.addon.modules.AutoCrop;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AutocropAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("AutoCrop");

    @Override
    public void onInitialize() {
        LOG.info("Initializing AutoCrop Addon");

        // Регистрация модуля
        Modules.get().add(new AutoCrop());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }
}
