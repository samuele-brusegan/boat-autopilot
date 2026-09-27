package com.boatpilot.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.KeyMapping;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;

public final class BoatPilotClientEvents {
    private static KeyMapping stopKey;

    private BoatPilotClientEvents() {
    }

    static void setStopKey(KeyMapping key) {
        stopKey = key;
    }

    public static void tick(TickEvent.ClientTickEvent.Pre event) {
        if (stopKey != null) {
            while (stopKey.consumeClick()) AutopilotController.stopByUser();
        }
        AutopilotController.tick();
    }

    public static void manualInput(InputEvent.Key event) {
        if (event.getAction() != InputConstants.PRESS) return;
        var info = event.getInfo();
        var options = net.minecraft.client.Minecraft.getInstance().options;
        if (options.keyUp.matches(info) || options.keyDown.matches(info)
            || options.keyLeft.matches(info) || options.keyRight.matches(info)) {
            AutopilotController.manualInput();
        }
    }

    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("boatpilot")
            .then(Commands.literal("waypoint")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(context -> {
                        AutopilotController.selectWaypoint(StringArgumentType.getString(context, "name"));
                        return 1;
                    })))
            .then(Commands.literal("go")
                .then(Commands.argument("position", BlockPosArgument.blockPos())
                    .executes(context -> {
                        BlockPos pos = BlockPosArgument.getBlockPos(context, "position");
                        AutopilotController.requestRoute(pos);
                        return 1;
                    })))
            .then(Commands.literal("stop").executes(context -> {
                AutopilotController.stopByUser();
                return 1;
            })));
    }
}
