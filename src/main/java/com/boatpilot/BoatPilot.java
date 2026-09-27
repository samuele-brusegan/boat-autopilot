package com.boatpilot;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(BoatPilot.MOD_ID)
public final class BoatPilot {
    public static final String MOD_ID = "boatpilot";

    public BoatPilot() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.boatpilot.client.BoatPilotModEvents.register();
        }
    }
}
