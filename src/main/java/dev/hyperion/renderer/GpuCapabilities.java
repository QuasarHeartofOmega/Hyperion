package dev.hyperion.renderer;

import dev.hyperion.core.Hyperion;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

/**
 * Диагностика GPU и честный статус «Vulkan-бэкенда».
 *
 * <p>Почему в этом моде нет замены OpenGL на Vulkan/DX12:
 * <ul>
 *   <li>весь рендер Minecraft 1.21.4 — это Blaze3D поверх OpenGL 3.2 core через LWJGL;
 *       замена API означает переписывание рендера игры, а не «мод»;</li>
 *   <li>в поставке игры нет модуля {@code lwjgl-vulkan}, нет и нативных библиотек;</li>
 *   <li>mesh shaders / ray tracing требуют расширений, которых нет у большинства
 *       пользователей, и не убирают главное узкое место — CPU-подготовку кадра;</li>
 *   <li>шейдеры Iris/OptiFine — это GLSL, они физически не запустятся на Vulkan.</li>
 * </ul>
 * Поэтому здесь только диагностика: что умеет GPU и чего нет в рантайме.
 */
public final class GpuCapabilities {

    private static volatile boolean probed;
    private static String vendor = "?";
    private static String renderer = "?";
    private static String version = "?";
    private static boolean meshShaders;
    private static boolean bindless;
    private static boolean rayTracing;
    private static int extensionCount;

    private GpuCapabilities() {
    }

    /** Вызывать только из render thread. */
    public static void probe() {
        if (probed) return;
        probed = true;
        try {
            vendor = safe(GL11.GL_VENDOR);
            renderer = safe(GL11.GL_RENDERER);
            version = safe(GL11.GL_VERSION);
            List<String> extensions = extensions();
            extensionCount = extensions.size();
            for (String ext : extensions) {
                if (ext.contains("mesh_shader")) meshShaders = true;
                if (ext.contains("bindless_texture")) bindless = true;
                if (ext.contains("ray_query") || ext.contains("ray_tracing")) rayTracing = true;
            }
            Hyperion.LOGGER.info("[Hyperion][GPU] {} / {} / {} (расширений: {})", vendor, renderer, version, extensionCount);
            Hyperion.LOGGER.info("[Hyperion][GPU] mesh_shaders={}, bindless={}, ray_tracing={} — Hyperion их не использует, рендер остаётся OpenGL",
                    meshShaders, bindless, rayTracing);
        } catch (Throwable t) {
            Hyperion.LOGGER.warn("[Hyperion][GPU] Не удалось прочитатьcapabilities: {}", t.toString());
        }
    }

    private static String safe(int pname) {
        String value = GL11.glGetString(pname);
        return value == null ? "?" : value;
    }

    private static List<String> extensions() {
        List<String> list = new ArrayList<>();
        try {
            String all = GL11.glGetString(GL11.GL_EXTENSIONS);
            if (all != null && !all.isEmpty()) {
                for (String s : all.split(" ")) list.add(s);
                return list;
            }
            int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            for (int i = 0; i < count; i++) {
                String ext = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
                if (ext != null) list.add(ext);
            }
        } catch (Throwable ignored) {
            // core-профиль без GL_EXTENSIONS — не критично
        }
        return list;
    }

    public static String vulkanStatus() {
        try {
            Class.forName("org.lwjgl.vulkan.VK10");
            return "LWJGL Vulkan найден, но бэкенд Hyperion НЕ реализован (рендер Minecraft остаётся OpenGL)";
        } catch (ClassNotFoundException e) {
            return "недоступен: модуль lwjgl-vulkan не входит в поставку Minecraft 1.21.4";
        }
    }

    public static String describe() {
        if (!probed) return "GPU: ещё не опрошен (нужен кадр рендера)";
        return String.format("%s %s | GL %s | ext=%d mesh=%s bindless=%s rt=%s",
                vendor, renderer, version, extensionCount, meshShaders, bindless, rayTracing);
    }

    public static boolean isMeshShaderCapable() {
        return meshShaders;
    }
}
