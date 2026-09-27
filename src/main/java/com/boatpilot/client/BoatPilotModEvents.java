package com.boatpilot.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;

public final class BoatPilotModEvents {
    private static final KeyMapping STOP_KEY = new KeyMapping(
        "key.boatpilot.stop", InputConstants.KEY_O, KeyMapping.Category.MISC);

    private BoatPilotModEvents() {
    }

    public static void register() {
        RegisterKeyMappingsEvent.BUS.addListener(BoatPilotModEvents::registerKeys);
        RegisterClientCommandsEvent.BUS.addListener(BoatPilotClientEvents::registerCommands);
        TickEvent.ClientTickEvent.Pre.BUS.addListener(BoatPilotClientEvents::tick);
        InputEvent.Key.BUS.addListener(BoatPilotClientEvents::manualInput);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(STOP_KEY);
        BoatPilotClientEvents.setStopKey(STOP_KEY);
    }
}
