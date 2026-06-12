package dev.upscaler;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UpscalerMod implements ModInitializer {
	public static final String MOD_ID = "upscaler";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Sodium Upscaler initialized (common)");
	}
}
