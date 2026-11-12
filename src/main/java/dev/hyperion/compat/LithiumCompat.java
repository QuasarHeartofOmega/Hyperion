package dev.hyperion.compat;

/**
 * Мост к Lithium.
 *
 * <p>Lithium и Hyperion почти не пересекаются, и это важно понимать: Lithium
 * оптимизирует коллекции сущностей, формы блоков, POI, инвентари и hopper'ы,
 * но НЕ заменяет ни GoalSelector.tick, ни RegionFile, ни рендер сущностей
 * (проверено по списку миксинов Lithium 0.15.3 для 1.21.4).
 *
 * <p>Поэтому «переопределять методы Lithium» не нужно и вредно: его оптимизации
 * остаются, а Hyperion работает поверх. Единственная реальная точка пересечения —
 * ServerWorld.tickEntity, куда Lithium ставит свои коллекции, а Hyperion снимает
 * снапшот для параллельного анализа: наш хук @Inject(HEAD) ничего не отменяет.
 *
 * <p>Отдельно: у Lithium есть опция {@code mixin.entity.inactive_navigations},
 * которая сама «замораживает» навигацию неактивных мобов. Hyperion пропускает
 * только выбор целей, навигацию и физику не трогает — конфликта нет.
 */
public final class LithiumCompat {

    private static final String MARKER = "net.caffeinemc.mods.lithium.common.LithiumMod";
    private static final String MARKER_OLD = "me.jellysquid.mods.lithium.common.LithiumMod";
    private static final String CONFIG = "net.caffeinemc.mods.lithium.common.config.LithiumConfig";

    private LithiumCompat() {
    }

    public static boolean present() {
        return CompatBridge.exists(MARKER, MARKER_OLD, CONFIG);
    }

    public static String describe() {
        if (!present()) return "Lithium не установлен";
        return "Lithium: оптимизации сохранены, Hyperion работает поверх "
                + "(GoalSelector/RegionFile Lithium не заменяет)";
    }
}
