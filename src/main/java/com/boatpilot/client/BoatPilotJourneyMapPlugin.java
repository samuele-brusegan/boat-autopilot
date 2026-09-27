package com.boatpilot.client;

import com.boatpilot.BoatPilot;
import com.boatpilot.maps.JourneyMapMapSource;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.event.FullscreenMapEvent;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.FullscreenEventRegistry;
import journeymap.api.v2.client.IClientPlugin;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;

@JourneyMapPlugin(apiVersion = IClientAPI.API_VERSION)
public final class BoatPilotJourneyMapPlugin implements IClientPlugin {
    @Override
    public String getModId() {
        return BoatPilot.MOD_ID;
    }

    @Override
    public void initialize(IClientAPI api) {
        JourneyMapMapSource source = new JourneyMapMapSource(api);
        AutopilotController.initialize(source, source);
        FullscreenEventRegistry.FULLSCREEN_MAP_CLICK_EVENT.subscribe(
            BoatPilot.MOD_ID, this::onMapClick);
    }

    private void onMapClick(FullscreenMapEvent.ClickEvent event) {
        if (event.getStage() != FullscreenMapEvent.Stage.PRE
            || event.getButton() != InputConstants.MOUSE_BUTTON_LEFT) {
            return;
        }
        boolean shift = InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)
            || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
        if (!shift) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !event.getLevel().equals(minecraft.level.dimension())) {
            if (minecraft.player != null) {
                minecraft.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
                    "Seleziona un punto nella dimensione attuale."));
            }
            return;
        }
        AutopilotController.requestRoute(event.getLocation());
    }
}
