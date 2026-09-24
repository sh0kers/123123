package ru.holyworld.tntbuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HolyWorldTntBuilder implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("HolyWorldTntBuilder");
    public static Config CONFIG = new Config();

    private static KeyBinding actionKey;
    private final Macro macro = new Macro();

    @Override
    public void onInitializeClient() {
        CONFIG = Config.load();

        actionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.holyworld.tntbuilder",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.holyworld"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (actionKey.wasPressed()) {
                macro.toggle(client);
            }
            macro.tick(client);
        });

        LOGGER.info("HolyWorld TNT Builder загружен");
    }
}
