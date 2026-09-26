package dev.comfyfluffy.caustica.minecraft.client;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders components the way an English client does: the vanilla and Caustica {@code en_us.json} files,
 * measured with the default font's ASCII bitmap.
 *
 * <p>Resources are read through the context class loader: under NeoForge's test runtime the game jar is its own
 * module, and neither this class nor the game's classes resolve the font texture through {@code Class}.
 */
public final class EnglishText implements AutoCloseable {
    /** The space provider's advance, which precedes the bitmap in the default font. */
    private static final int SPACE_ADVANCE = 4;
    /** Wider than any unifont glyph a label here uses, so a non-ASCII character never under-measures. */
    private static final int NON_ASCII_ADVANCE = 9;
    private static final int[] ASCII_ADVANCES = asciiAdvances();

    private final Language previous = Language.getInstance();

    private EnglishText() {
        Map<String, String> english = new HashMap<>();
        load("assets/minecraft/lang/en_us.json", english);
        load("assets/caustica/lang/en_us.json", english);
        Language.inject(new Language() {
            @Override public String getOrDefault(String key, String fallback) {
                return english.getOrDefault(key, fallback);
            }
            @Override public boolean has(String key) { return english.containsKey(key); }
            @Override public boolean isDefaultRightToLeft() { return false; }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) {
                return FormattedCharSequence.forward(text.getString(), Style.EMPTY);
            }
        });
    }

    /** Installs English as the active language until {@link #close()}. */
    public static EnglishText install() {
        return new EnglishText();
    }

    @Override
    public void close() {
        Language.inject(previous);
    }

    /** Width in GUI pixels, as {@code Font.width} returns for unstyled default-font text. */
    public static int width(FormattedText text) {
        return text.getString().chars()
                .map(c -> c == ' ' ? SPACE_ADVANCE : c < 128 ? ASCII_ADVANCES[c] : NON_ASCII_ADVANCE).sum();
    }

    private static InputStream resource(String name) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
    }

    private static void load(String name, Map<String, String> into) {
        try (InputStream stream = resource(name)) {
            Language.loadFromJson(stream, into::put);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Bitmap provider metrics: the rightmost opaque column of each 8x8 cell, plus one pixel of spacing. */
    private static int[] asciiAdvances() {
        try (InputStream stream = resource("assets/minecraft/textures/font/ascii.png")) {
            BufferedImage image = ImageIO.read(stream);
            int cell = image.getWidth() / 16;
            int[] advances = new int[128];
            for (int c = 0; c < 128; c++) {
                int width = 0;
                for (int x = cell - 1; x >= 0 && width == 0; x--) {
                    for (int y = 0; y < cell; y++) {
                        if ((image.getRGB((c % 16) * cell + x, (c / 16) * cell + y) >>> 24) != 0) {
                            width = x + 1;
                            break;
                        }
                    }
                }
                advances[c] = width * 8 / cell + 1;
            }
            return advances;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
