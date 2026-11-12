package dev.hyperion.core;

import dev.hyperion.compat.HyperionCompat;
import dev.hyperion.memory.BlockStateCache;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа (общая часть: сервер и клиент).
 *
 * <p>Мод не требует Fabric API — все хуки сделаны миксинами в ванильные классы.
 */
public final class Hyperion implements ModInitializer {

    public static final String MOD_ID = "hyperion";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LoggerFactory.getLogger("Hyperion");

    @Override
    public void onInitialize() {
        HyperionConfig.init(FabricLoader.getInstance().getConfigDir());
        HyperionConfig cfg = HyperionConfig.get();

        LOGGER.info("[Hyperion] {} для Minecraft 1.21.4 (Fabric). enabled={}", VERSION, cfg.enabled);

        HyperionCompat.scan();
        HyperionCompat.logReport();

        if (!cfg.enabled) {
            LOGGER.warn("[Hyperion] Мод выключен в конфиге (enabled=false) — оптимизации не применяются.");
            return;
        }

        HyperionExecutors.start();
        BlockStateCache.requestInit();

        LOGGER.info("[Hyperion] Активные подсистемы: chunk-prefetch={}, entity-throttling={}, blockstate-cache={}",
                cfg.chunkPrefetch && !HyperionCompat.isDisabled(HyperionCompat.FEATURE_CHUNK_IO),
                cfg.entityThrottling,
                cfg.blockStateCache);
    }
}
