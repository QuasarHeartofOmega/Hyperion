package dev.hyperion.compat;

import dev.hyperion.core.Hyperion;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Безопасный мост к чужим модам через рефлексию.
 *
 * <p>Почему рефлексия, а не compileOnly-зависимости: Hyperion не должен требовать
 * Sodium/Iris/Lithium ни при сборке, ни при запуске. Мод ставится в любую сборку —
 * если класса нет, мост просто возвращает «недоступно», и подсистема работает
 * в обычном режиме. Все обращения кэшируются, все исключения гасятся.
 *
 * <p>Ни один метод этого класса не бросает исключений наружу.
 */
public final class CompatBridge {

    /** Кэш «класс найден/не найден». Отсутствие класса — тоже результат. */
    private static final Map<String, Optional<Class<?>>> CLASSES = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Field>> FIELDS = new ConcurrentHashMap<>();

    private CompatBridge() {
    }

    public static ClassLoader loader() {
        ClassLoader cl = CompatBridge.class.getClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }

    /** Первый существующий класс из списка (у разных версий мода разные пакеты). */
    public static Class<?> find(String... names) {
        for (String name : names) {
            Class<?> found = lookup(name);
            if (found != null) return found;
        }
        return null;
    }

    public static boolean exists(String... names) {
        return find(names) != null;
    }

    private static Class<?> lookup(String name) {
        Optional<Class<?>> cached = CLASSES.get(name);
        if (cached != null) return cached.orElse(null);
        Class<?> resolved;
        try {
            // initialize=false: не запускаем static-инициализацию чужого мода раньше времени.
            resolved = Class.forName(name, false, loader());
        } catch (Throwable t) {
            resolved = null;
        }
        CLASSES.put(name, Optional.ofNullable(resolved));
        return resolved;
    }

    public static Method method(Class<?> owner, String name, Class<?>... params) {
        if (owner == null) return null;
        String key = owner.getName() + '#' + name + paramKey(params);
        Optional<Method> cached = METHODS.get(key);
        if (cached != null) return cached.orElse(null);
        Method resolved = null;
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            resolved = m;
        } catch (Throwable ignored) {
            // Версия мода изменилась — молча считаем метод недоступным.
        }
        METHODS.put(key, Optional.ofNullable(resolved));
        return resolved;
    }

    /** Поиск метода только по имени (первый подходящий) — для нестабильных сигнатур. */
    public static Method methodByName(Class<?> owner, String name) {
        if (owner == null) return null;
        String key = owner.getName() + '#' + name + "*";
        Optional<Method> cached = METHODS.get(key);
        if (cached != null) return cached.orElse(null);
        Method resolved = null;
        try {
            for (Method m : owner.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    resolved = m;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        METHODS.put(key, Optional.ofNullable(resolved));
        return resolved;
    }

    public static Field field(Class<?> owner, String name) {
        if (owner == null) return null;
        String key = owner.getName() + '.' + name;
        Optional<Field> cached = FIELDS.get(key);
        if (cached != null) return cached.orElse(null);
        Field resolved = null;
        Class<?> cursor = owner;
        while (cursor != null && resolved == null) {
            try {
                Field f = cursor.getDeclaredField(name);
                f.setAccessible(true);
                resolved = f;
            } catch (Throwable ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        FIELDS.put(key, Optional.ofNullable(resolved));
        return resolved;
    }

    public static Object invoke(Method method, Object instance, Object... args) {
        if (method == null) return null;
        try {
            return method.invoke(instance, args);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean invokeBoolean(Method method, Object instance, boolean fallback, Object... args) {
        Object value = invoke(method, instance, args);
        return value instanceof Boolean b ? b : fallback;
    }

    public static String invokeString(Method method, Object instance, String fallback, Object... args) {
        Object value = invoke(method, instance, args);
        return value instanceof String s ? s : fallback;
    }

    public static Object read(Field field, Object instance) {
        if (field == null) return null;
        try {
            return field.get(instance);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean readBoolean(Field field, Object instance, boolean fallback) {
        Object value = read(field, instance);
        return value instanceof Boolean b ? b : fallback;
    }

    public static String readString(Field field, Object instance, String fallback) {
        Object value = read(field, instance);
        return value instanceof String s ? s : fallback;
    }

    public static boolean write(Field field, Object instance, Object value) {
        if (field == null) return false;
        try {
            field.set(instance, value);
            return true;
        } catch (Throwable t) {
            Hyperion.LOGGER.debug("[Hyperion][compat] не удалось записать {}: {}", field, String.valueOf(t));
            return false;
        }
    }

    /** Вызов статического метода, возвращающего экземпляр (getInstance/instance/options). */
    public static Object singleton(String className, String... factoryNames) {
        Class<?> owner = find(className);
        if (owner == null) return null;
        for (String name : factoryNames) {
            Method m = method(owner, name);
            Object value = invoke(m, null);
            if (value != null) return value;
        }
        return null;
    }

    private static String paramKey(Class<?>[] params) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) sb.append(p == null ? '?' : p.getName()).append(',');
        return sb.append(')').toString();
    }
}
