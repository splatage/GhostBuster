package com.splatage.ghostbuster.platform;

import org.bukkit.World;
import java.util.UUID;
import java.util.function.Consumer;

public interface SchedulerFacade {
  void runGlobalSync(Runnable r);
  void runLaterSync(long ticks, Runnable r);
  void runAt(World world, int blockX, int blockZ, Runnable r);
  void withEntityWorld(UUID uuid, Consumer<World> action);
}
