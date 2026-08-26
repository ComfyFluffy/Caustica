package dev.comfyfluffy.caustica.client;

/**
 * Sub-pixel camera jitter for DLSS Ray Reconstruction.
 *
 * <p>Generates a Halton(2,3) low-discrepancy sequence in render-pixel space, with the DLSS phase-count
 * rule {@code ceil(8 * (display/render)^2)} and RR's recommended floor of 32 phases.
 * {@link dev.comfyfluffy.caustica.rt.RtComposite} reads the per-frame offset, applies it to the primary ray in the
 * path-tracing shader, and reports it to DLSS-RR's evaluate.
 */
public final class CausticaJitter {
	public static final CausticaJitter INSTANCE = new CausticaJitter();

	private int frameIndex;
	private int phaseCount;
	private float pixelsX;
	private float pixelsY;

	private CausticaJitter() {
	}

	/** Advance one frame. Call once per frame before the level projection is built. */
	public void prepare(int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
		this.phaseCount = jitterPhaseCount(renderWidth, renderHeight, displayWidth, displayHeight);
		int index = (this.frameIndex++ % this.phaseCount) + 1; // Halton(0) is degenerate
		this.pixelsX = halton(index, 2) - 0.5f;
		this.pixelsY = halton(index, 3) - 0.5f;
	}

	/** Advance using a square display scale for callers that do not have the display height. */
	public void prepare(int renderWidth, int renderHeight, int displayWidth) {
		prepare(renderWidth, renderHeight, displayWidth, displayWidth);
	}

	/** Jitter offset in render-pixel space, applied to the primary ray and reported to RR evaluate. */
	public float jitterPixelsX() {
		return this.pixelsX;
	}

	public float jitterPixelsY() {
		return this.pixelsY;
	}

	/** Reset the sequence and clear the current offset before a temporal A/B comparison. */
	public void reset() {
		this.frameIndex = 0;
		this.phaseCount = 0;
		this.pixelsX = 0.0f;
		this.pixelsY = 0.0f;
	}

	public int currentPhaseCount() {
		return this.phaseCount;
	}

	static int jitterPhaseCount(int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
		float ratioX = (float) displayWidth / Math.max(1, renderWidth);
		float ratioY = (float) displayHeight / Math.max(1, renderHeight);
		float ratio = Math.max(ratioX, ratioY);
		return Math.max(32, (int) Math.ceil(8.0f * ratio * ratio));
	}

	private static float halton(int index, int base) {
		float f = 1.0f;
		float result = 0.0f;
		int i = index;
		while (i > 0) {
			f /= base;
			result += f * (i % base);
			i /= base;
		}
		return result;
	}
}
