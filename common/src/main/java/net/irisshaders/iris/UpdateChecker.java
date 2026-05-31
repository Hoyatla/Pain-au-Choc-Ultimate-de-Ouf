package net.irisshaders.iris;

import net.irisshaders.iris.config.IrisConfig;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

public class UpdateChecker {
	public UpdateChecker(String currentVersion) {
		// PauC: update prompts are intentionally disabled.
	}

	public void checkForUpdates(IrisConfig irisConfig) {
		// PauC: update prompts are intentionally disabled.
	}

	@Nullable
	public UpdateInfo getUpdateInfo() {
		return null;
	}

	@Nullable
	public Optional<BetaInfo> getBetaInfo() {
		return Optional.empty();
	}

	public Optional<Component> getUpdateMessage() {
		return Optional.empty();
	}

	public Optional<String> getUpdateLink() {
		return Optional.empty();
	}

	static class UpdateInfo {
		public String semanticVersion;
		public Map<String, String> updateInfo;
		public String modHost;
		public String modDownload;
		public String installer;
	}

	public static class BetaInfo {
		public String betaTag;
		public int betaVersion;
	}
}
