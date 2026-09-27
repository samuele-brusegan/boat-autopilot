package com.boatpilot.maps;

import com.boatpilot.navigation.MapRaster;
import net.minecraft.core.BlockPos;

import java.util.Optional;
import java.util.function.Consumer;

public interface MapSource {
    void request(BlockPos from, BlockPos to, Consumer<Optional<MapRaster>> callback);
}
