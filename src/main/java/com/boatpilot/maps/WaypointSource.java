package com.boatpilot.maps;

import net.minecraft.core.BlockPos;

import java.util.Optional;
import java.util.function.Consumer;

public interface WaypointSource {
    void findByName(String name, Consumer<Optional<BlockPos>> callback);
}
