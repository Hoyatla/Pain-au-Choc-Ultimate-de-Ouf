package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.common.AbstractModInitializer$IEventProxy_forge;
import com.seibel.distanthorizons.common.AbstractModInitializer_forge;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IModChecker;
import com.seibel.distanthorizons.forge.ForgeClientProxy;
import com.seibel.distanthorizons.forge.ForgePluginPacketSender;
import com.seibel.distanthorizons.forge.ForgeServerProxy;
import com.seibel.distanthorizons.forge.wrappers.modAccessor.ModChecker;
import fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.util.function.Consumer;

public final class PauCEmbeddedDhBootstrap {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static volatile boolean initialized;

	private PauCEmbeddedDhBootstrap() {
	}

	public static synchronized void bootstrapClient() {
		if (initialized || !FMLEnvironment.dist.isClient()) {
			return;
		}

		initialized = true;
		PauCEmbeddedDhRuntime.markBootstrapStarted();
		try {
			new PauCOwnedDhInitializer().onInitializeClient();
			PauCEmbeddedDhRuntime.markInitialized();
			LOGGER.info("PauC embedded Distant Horizons client bootstrap completed without exposing DH as a Forge mod.");
		} catch (Throwable throwable) {
			initialized = false;
			PauCEmbeddedDhRuntime.markUnavailable();
			LOGGER.warn("PauC embedded Distant Horizons client bootstrap failed; LOD bridge will stay inactive.", throwable);
		}
	}

	private static final class PauCOwnedDhInitializer extends AbstractModInitializer_forge {
		@Override
		protected void createInitialSharedBindings() {
			SingletonInjector.INSTANCE.bind(IModChecker.class, ModChecker.INSTANCE);
			SingletonInjector.INSTANCE.bind(IPluginPacketSender.class, new ForgePluginPacketSender());
		}

		@Override
		protected void createInitialClientBindings() {
		}

		@Override
		protected AbstractModInitializer$IEventProxy_forge createClientProxy() {
			return new ForgeClientProxy();
		}

		@Override
		protected AbstractModInitializer$IEventProxy_forge createServerProxy(boolean isDedicated) {
			return new ForgeServerProxy(isDedicated);
		}

		@Override
		protected void initializeModCompat() {
			// PauC owns public configuration and shader compatibility while DH is embedded.
		}

		@Override
		protected void subscribeRegisterCommandsEvent(Consumer<CommandDispatcher<CommandSourceStack>> eventHandler) {
			MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> eventHandler.accept(event.getDispatcher()));
		}

		@Override
		protected void subscribeClientStartedEvent(Runnable eventHandler) {
			eventHandler.run();
		}

		@Override
		protected void subscribeServerStartingEvent(Consumer<MinecraftServer> eventHandler) {
			MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, (ServerAboutToStartEvent event) -> eventHandler.accept(event.getServer()));
		}

		@Override
		protected void runDelayedSetup() {
			SingletonInjector.INSTANCE.runDelayedSetup();
		}
	}
}
