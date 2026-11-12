package dev.hyperion.entity.path;

import dev.hyperion.core.Hyperion;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.mob.MobEntity;

import java.lang.reflect.Field;

/**
 * Доступ к владельцу навигации ({@code EntityNavigation.entity}) из миксина.
 *
 * <p><b>Почему не {@code @Shadow}.</b> Проверено на этом же toolchain: Mixin Annotation
 * Processor пишет в refmap запись для метода-инжекта, но <b>не</b> для {@code @Shadow}-поля.
 * В intermediary-рантайме поле осталось бы называться {@code entity}, а в игре оно
 * {@code field_4940} — Mixin не нашёл бы цель и уронил загрузку (InvalidMixinException).
 * Ровно эта причина описана в {@code CommandManagerMixin}.
 *
 * <p>Решение: один раз найти поле <b>по типу</b>, а не по имени. Тип
 * ({@code MobEntity}) одинаков в named- и intermediary-среде, поэтому поиск не зависит
 * от маппингов и переживает обновление Yarn. Поле ищется в самом {@code EntityNavigation},
 * а не в подклассах — оно объявлено именно там.
 *
 * <p>Если поле найти не удалось (мод-форк переписал навигацию), подсистема молча
 * выключается: миксин возвращает null и ваниль считает путь сама.
 */
public final class NavigationAccess {

    private static volatile Field entityField;
    private static volatile boolean unavailable;

    private NavigationAccess() {
    }

    /** @return моб-владелец навигации или null, если доступ недоступен. */
    public static MobEntity entityOf(EntityNavigation navigation) {
        if (unavailable || navigation == null) return null;
        Field field = entityField;
        if (field == null) {
            field = resolve();
            if (field == null) return null;
        }
        try {
            Object value = field.get(navigation);
            return value instanceof MobEntity mob ? mob : null;
        } catch (Throwable t) {
            unavailable = true;
            Hyperion.LOGGER.warn("[Hyperion] Доступ к владельцу навигации недоступен, асинхронный поиск пути выключен: {}",
                    t.toString());
            return null;
        }
    }

    private static synchronized Field resolve() {
        if (entityField != null) return entityField;
        if (unavailable) return null;
        try {
            for (Field field : EntityNavigation.class.getDeclaredFields()) {
                if (field.getType() == MobEntity.class) {
                    field.setAccessible(true);
                    entityField = field;
                    return field;
                }
            }
            unavailable = true;
            Hyperion.LOGGER.warn("[Hyperion] В EntityNavigation нет поля MobEntity — асинхронный поиск пути выключен.");
        } catch (Throwable t) {
            unavailable = true;
            Hyperion.LOGGER.warn("[Hyperion] Не удалось получить доступ к EntityNavigation: {}", t.toString());
        }
        return null;
    }

    /** Для тестов/диагностики: доступен ли мост. */
    public static boolean available() {
        return !unavailable;
    }
}
