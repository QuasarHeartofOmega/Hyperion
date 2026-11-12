package dev.hyperion.renderer;

/**
 * Геометрия индикатора — чистая математика без Minecraft, покрыта автономными тестами.
 *
 * <p>Индикатор крепится к одному из четырёх углов экрана (в координатах GUI,
 * то есть уже поделенных на guiScale) и никогда не выезжает за границы: если
 * панель шире экрана, она прижимается к левому краю.
 */
public final class IndicatorLayout {

    public enum Corner {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

        public static Corner parse(String value, Corner fallback) {
            if (value == null) return fallback;
            String normalized = value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
            for (Corner corner : values()) {
                if (corner.name().equals(normalized)) return corner;
            }
            return fallback;
        }

        public boolean right() {
            return this == TOP_RIGHT || this == BOTTOM_RIGHT;
        }

        public boolean bottom() {
            return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
        }
    }

    /** Прямоугольник панели в GUI-координатах. */
    public record Rect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }

    private IndicatorLayout() {
    }

    public static Rect place(Corner corner, int screenWidth, int screenHeight,
                             int width, int height, int offsetX, int offsetY) {
        int x = corner.right() ? screenWidth - width - offsetX : offsetX;
        int y = corner.bottom() ? screenHeight - height - offsetY : offsetY;
        return new Rect(clamp(x, 0, Math.max(0, screenWidth - width)),
                clamp(y, 0, Math.max(0, screenHeight - height)),
                width, height);
    }

    /** Панель под текстовые строки: паддинг 3 px, высота строки 10 px. */
    public static Rect panel(Corner corner, int screenWidth, int screenHeight,
                             int textWidth, int lines, int offsetX, int offsetY) {
        int width = textWidth + 8;
        int height = lines * 10 + 6;
        return place(corner, screenWidth, screenHeight, width, height, offsetX, offsetY);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
