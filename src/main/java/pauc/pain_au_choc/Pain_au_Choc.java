//noinspection SpellCheckingInspection
package pauc.pain_au_choc;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Pain_au_Choc.MOD_ID)
@SuppressWarnings("SpellCheckingInspection")
public class Pain_au_Choc {
    public static final String MOD_ID = "pauc";
    static final Logger LOGGER = LogUtils.getLogger();

    public Pain_au_Choc(FMLJavaModLoadingContext context) {
        PauCClient.initialize();
        context.getModEventBus().addListener(PauCClient::onRegisterKeys);
        context.getModEventBus().addListener(PauCShaderManager::onRegisterShaders);
        LOGGER.info("Pain au Choc ultimate de Ouf loaded (Forge 1.20.1)");
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ClientEvents {
        private ClientEvents() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            PauCClient.onClientTick(event);
        }

        @SubscribeEvent
        public static void onServerTick(TickEvent.ServerTickEvent event) {
            IntegratedServerLoadController.onServerTick(event);
        }

        @SubscribeEvent
        public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
            PauCClient.onClientEntityJoin(event);
        }
    }
}


