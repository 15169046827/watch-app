package com.canace.x3voiceledger.phone;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class PaymentScreenshotRecognizer {
    private static final int GRID_W = 28;
    private static final int GRID_H = 42;
    private static final boolean[][][] DIGIT_TEMPLATES = buildTemplates();

    private PaymentScreenshotRecognizer() { }

    static long recognizeAlipayAmount(Bitmap bitmap) {
        if (!isAlipayPaymentLayout(bitmap)) {
            return -1L;
        }
        List<Component> components = amountComponents(bitmap);
        if (components.isEmpty()) {
            return -1L;
        }
        Collections.sort(components, Comparator.comparingInt(value -> value.left));
        int minDigitHeight = Math.max(30, bitmap.getHeight() * 9 / 100);
        AmountText amount = new AmountText();
        for (Component component : components) {
            if (!amount.accept(bitmap, component, minDigitHeight)) {
                return -1L;
            }
        }
        return parseAmount(amount.value());
    }

    private static long parseAmount(String value) {
        if (!value.matches("\\d{1,6}\\.\\d{2}")) {
            return -1L;
        }
        try {
            return Math.round(Double.parseDouble(value) * 100d);
        } catch (RuntimeException error) {
            return -1L;
        }
    }
    private static boolean isAlipayPaymentLayout(Bitmap bitmap) {
        if (bitmap == null
                || bitmap.getWidth() < 300
                || bitmap.getHeight() < 300
                || Math.abs(bitmap.getWidth() - bitmap.getHeight()) > 8) {
            return false;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (luminance(bitmap.getPixel(width / 2, height / 2)) > 80
                || luminance(bitmap.getPixel(2, 2)) < 180) {
            return false;
        }
        int blue = 0;
        for (int y = height / 10; y < height * 34 / 100; y += 2) {
            for (int x = width / 3; x < width * 2 / 3; x += 2) {
                int color = bitmap.getPixel(x, y);
                int red = Color.red(color);
                int green = Color.green(color);
                int value = Color.blue(color);
                if (value > 130 && value > red * 3 / 2 && value > green * 11 / 10) {
                    blue++;
                }
            }
        }
        return blue > 30;
    }

    private static List<Component> amountComponents(Bitmap bitmap) {
        int left = bitmap.getWidth() * 18 / 100;
        int top = bitmap.getHeight() * 38 / 100;
        int width = bitmap.getWidth() * 84 / 100 - left;
        int height = bitmap.getHeight() * 61 / 100 - top;
        boolean[] mask = amountMask(bitmap, left, top, width, height);
        boolean[] visited = new boolean[mask.length];
        int[] queue = new int[mask.length];
        List<Component> result = new ArrayList<>();
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || visited[start]) {
                continue;
            }
            Component component =
                    floodComponent(mask, visited, queue, start, width, height, left, top);
            if (isAmountComponent(bitmap, component)) {
                result.add(component);
            }
        }
        return result;
    }

    private static boolean[] amountMask(
            Bitmap bitmap, int left, int top, int width, int height) {
        boolean[] mask = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                mask[y * width + x] = isWhite(bitmap.getPixel(left + x, top + y));
            }
        }
        return mask;
    }

    private static Component floodComponent(
            boolean[] mask,
            boolean[] visited,
            int[] queue,
            int start,
            int width,
            int height,
            int left,
            int top) {
        int head = 0;
        int tail = 0;
        queue[tail++] = start;
        visited[start] = true;
        int minX = width;
        int minY = height;
        int maxX = 0;
        int maxY = 0;
        int area = 0;
        while (head < tail) {
            int current = queue[head++];
            int x = current % width;
            int y = current / width;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            area++;
            if (x > 0) {
                tail = add(mask, visited, queue, tail, current - 1);
            }
            if (x + 1 < width) {
                tail = add(mask, visited, queue, tail, current + 1);
            }
            if (y > 0) {
                tail = add(mask, visited, queue, tail, current - width);
            }
            if (y + 1 < height) {
                tail = add(mask, visited, queue, tail, current + width);
            }
        }
        return new Component(left + minX, top + minY, left + maxX + 1, top + maxY + 1, area);
    }

    private static boolean isAmountComponent(Bitmap bitmap, Component component) {
        return component.area >= 10
                && component.top > bitmap.getHeight() * 39 / 100
                && component.bottom < bitmap.getHeight() * 60 / 100;
    }
    private static int add(boolean[] mask, boolean[] visited, int[] queue, int tail, int index) {
        if (mask[index] && !visited[index]) {
            visited[index] = true;
            queue[tail++] = index;
        }
        return tail;
    }

    private static int recognizeDigit(Bitmap bitmap, Component component) {
        boolean[][] candidate = normalizeBitmap(bitmap, component);
        int candidateHoles = countHoles(candidate);
        double candidateHoleCenter = holeCenterY(candidate);
        double best = 0d;
        int bestDigit = -1;
        for (int digit = 0; digit <= 9; digit++) {
            double score = similarity(candidate, DIGIT_TEMPLATES[digit]);
            int templateHoles = countHoles(DIGIT_TEMPLATES[digit]);
            score -= Math.min(0.50d, Math.abs(candidateHoles - templateHoles) * 0.25d);
            double templateHoleCenter = holeCenterY(DIGIT_TEMPLATES[digit]);
            if (candidateHoleCenter >= 0d && templateHoleCenter >= 0d) {
                score -= Math.abs(candidateHoleCenter - templateHoleCenter) * 2.2d;
            }
            if (score > best) {
                best = score;
                bestDigit = digit;
            }
        }
        if (candidateHoles == 1 && bestDigit == 0) {
            double upperRight = density(candidate, GRID_W / 2, 0, GRID_W, GRID_H / 2);
            double lowerRight = density(candidate, GRID_W / 2, GRID_H / 2, GRID_W, GRID_H);
            if (lowerRight - upperRight > 0.10d) {
                bestDigit = 6;
            }
        }
        return best >= 0.48d ? bestDigit : -1;
    }

    private static double density(boolean[][] glyph, int left, int top, int right, int bottom) {
        int count = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                if (glyph[y][x]) {
                    count++;
                }
            }
        }
        return count / (double) ((right - left) * (bottom - top));
    }

    private static int countHoles(boolean[][] glyph) {
        boolean[][] visited = new boolean[GRID_H][GRID_W];
        int holes = 0;
        int[] queue = new int[GRID_W * GRID_H];
        for (int startY = 0; startY < GRID_H; startY++) {
            for (int startX = 0; startX < GRID_W; startX++) {
                if (glyph[startY][startX] || visited[startY][startX]) {
                    continue;
                }
                if (!floodBackgroundRegion(glyph, visited, queue, startX, startY)) {
                    holes++;
                }
            }
        }
        return holes;
    }

    private static boolean floodBackgroundRegion(
            boolean[][] glyph,
            boolean[][] visited,
            int[] queue,
            int startX,
            int startY) {
        int head = 0;
        int tail = 0;
        queue[tail++] = startY * GRID_W + startX;
        visited[startY][startX] = true;
        boolean touchesEdge = false;
        while (head < tail) {
            int current = queue[head++];
            int x = current % GRID_W;
            int y = current / GRID_W;
            if (isGridEdge(x, y)) {
                touchesEdge = true;
            }
            if (x > 0) {
                tail = addBackground(glyph, visited, queue, tail, x - 1, y);
            }
            if (x + 1 < GRID_W) {
                tail = addBackground(glyph, visited, queue, tail, x + 1, y);
            }
            if (y > 0) {
                tail = addBackground(glyph, visited, queue, tail, x, y - 1);
            }
            if (y + 1 < GRID_H) {
                tail = addBackground(glyph, visited, queue, tail, x, y + 1);
            }
        }
        return touchesEdge;
    }

    private static boolean isGridEdge(int x, int y) {
        return x == 0 || y == 0 || x == GRID_W - 1 || y == GRID_H - 1;
    }
    private static int addBackground(
            boolean[][] glyph, boolean[][] visited, int[] queue, int tail, int x, int y) {
        if (!glyph[y][x] && !visited[y][x]) {
            visited[y][x] = true;
            queue[tail++] = y * GRID_W + x;
        }
        return tail;
    }

    private static double holeCenterY(boolean[][] glyph) {
        boolean[][] outside = new boolean[GRID_H][GRID_W];
        int[] queue = new int[GRID_W * GRID_H];
        int tail = markOutsideEdges(glyph, outside, queue);
        floodOutside(glyph, outside, queue, tail);
        return enclosedCenterY(glyph, outside);
    }

    private static int markOutsideEdges(
            boolean[][] glyph, boolean[][] outside, int[] queue) {
        int tail = 0;
        for (int x = 0; x < GRID_W; x++) {
            tail = addBackground(glyph, outside, queue, tail, x, 0);
            tail = addBackground(glyph, outside, queue, tail, x, GRID_H - 1);
        }
        for (int y = 0; y < GRID_H; y++) {
            tail = addBackground(glyph, outside, queue, tail, 0, y);
            tail = addBackground(glyph, outside, queue, tail, GRID_W - 1, y);
        }
        return tail;
    }

    private static void floodOutside(
            boolean[][] glyph, boolean[][] outside, int[] queue, int initialTail) {
        int head = 0;
        int tail = initialTail;
        while (head < tail) {
            int current = queue[head++];
            int x = current % GRID_W;
            int y = current / GRID_W;
            if (x > 0) {
                tail = addBackground(glyph, outside, queue, tail, x - 1, y);
            }
            if (x + 1 < GRID_W) {
                tail = addBackground(glyph, outside, queue, tail, x + 1, y);
            }
            if (y > 0) {
                tail = addBackground(glyph, outside, queue, tail, x, y - 1);
            }
            if (y + 1 < GRID_H) {
                tail = addBackground(glyph, outside, queue, tail, x, y + 1);
            }
        }
    }

    private static double enclosedCenterY(boolean[][] glyph, boolean[][] outside) {
        int count = 0;
        int yTotal = 0;
        for (int y = 0; y < GRID_H; y++) {
            for (int x = 0; x < GRID_W; x++) {
                if (!glyph[y][x] && !outside[y][x]) {
                    count++;
                    yTotal += y;
                }
            }
        }
        return count == 0 ? -1d : yTotal / (double) count / (GRID_H - 1);
    }
    private static boolean[][] normalizeBitmap(Bitmap bitmap, Component bounds) {
        boolean[][] result = new boolean[GRID_H][GRID_W];
        for (int gy = 0; gy < GRID_H; gy++) {
            int sourceY =
                    bounds.top
                            + Math.min(
                                    bounds.height() - 1,
                                    (gy * bounds.height() + bounds.height() / 2) / GRID_H);
            for (int gx = 0; gx < GRID_W; gx++) {
                int sourceX =
                        bounds.left
                                + Math.min(
                                        bounds.width() - 1,
                                        (gx * bounds.width() + bounds.width() / 2) / GRID_W);
                result[gy][gx] = isWhite(bitmap.getPixel(sourceX, sourceY));
            }
        }
        return result;
    }

    private static double similarity(boolean[][] left, boolean[][] right) {
        double best = 0d;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int intersection = 0;
                int total = 0;
                for (int y = 0; y < GRID_H; y++) {
                    for (int x = 0; x < GRID_W; x++) {
                        boolean a = left[y][x];
                        int ry = y + dy;
                        int rx = x + dx;
                        boolean b =
                                ry >= 0 && ry < GRID_H && rx >= 0 && rx < GRID_W && right[ry][rx];
                        if (a) {
                            total++;
                        }
                        if (b) {
                            total++;
                        }
                        if (a && b) {
                            intersection += 2;
                        }
                    }
                }
                if (total > 0) {
                    best = Math.max(best, intersection / (double) total);
                }
            }
        }
        return best;
    }

    private static boolean[][][] buildTemplates() {
        boolean[][][] result = new boolean[10][][];
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(120f);
        for (int digit = 0; digit <= 9; digit++) {
            Bitmap bitmap = Bitmap.createBitmap(140, 170, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.BLACK);
            String text = Integer.toString(digit);
            Rect textBounds = new Rect();
            paint.getTextBounds(text, 0, text.length(), textBounds);
            canvas.drawText(text, 10 - textBounds.left, 10 - textBounds.top, paint);
            Component bounds = whiteBounds(bitmap);
            result[digit] = normalizeBitmap(bitmap, bounds);
            bitmap.recycle();
        }
        return result;
    }

    private static Component whiteBounds(Bitmap bitmap) {
        int left = bitmap.getWidth();
        int top = bitmap.getHeight();
        int right = 0;
        int bottom = 0;
        int area = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (!isWhite(bitmap.getPixel(x, y))) {
                    continue;
                }
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x + 1);
                bottom = Math.max(bottom, y + 1);
                area++;
            }
        }
        return new Component(left, top, right, bottom, area);
    }

    private static boolean isWhite(int color) {
        return Color.red(color) > 155 && Color.green(color) > 155 && Color.blue(color) > 155;
    }

    private static int luminance(int color) {
        return (Color.red(color) * 3 + Color.green(color) * 6 + Color.blue(color)) / 10;
    }

    private static final class AmountText {
        private final StringBuilder value = new StringBuilder();
        private boolean started;
        private boolean decimal;

        boolean accept(Bitmap bitmap, Component component, int minDigitHeight) {
            if (component.height() >= minDigitHeight) {
                return acceptDigit(bitmap, component);
            }
            if (isDecimalPoint(bitmap, component)) {
                value.append('.');
                decimal = true;
            }
            return true;
        }

        private boolean acceptDigit(Bitmap bitmap, Component component) {
            int digit = recognizeDigit(bitmap, component);
            if (digit < 0) {
                return !started;
            }
            started = true;
            value.append(digit);
            return true;
        }

        private boolean isDecimalPoint(Bitmap bitmap, Component component) {
            return started
                    && !decimal
                    && component.width() <= bitmap.getWidth() / 25
                    && component.height() <= bitmap.getHeight() / 20
                    && component.top > bitmap.getHeight() * 48 / 100;
        }

        String value() {
            return value.toString();
        }
    }
    private static final class Component {
        final int left;
        final int top;
        final int right;
        final int bottom;
        final int area;

        Component(int left, int top, int right, int bottom, int area) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.area = area;
        }

        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }
    }
}
