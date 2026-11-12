package dev.hyperion.renderer;

/** Источник снапшотов для рейкаста. Отдельный интерфейс — чтобы движок был тестируемым. */
@FunctionalInterface
public interface SectionLookup {

    /** null, если секция неизвестна (тогда результат рейкаста — UNKNOWN). */
    SectionSnapshot get(long sectionKey);
}
