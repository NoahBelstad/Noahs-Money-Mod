package noahs.money.mod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;

public class PersistentHud {
    private static final Map<ServerPlayerEntity, Integer> moneyMap = new HashMap<>();

    // Call this during mod init
    public static void start() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Map.Entry<ServerPlayerEntity, Integer> entry : moneyMap.entrySet()) {
                ServerPlayerEntity player = entry.getKey();
                int money = entry.getValue();

                player.sendMessage(Text.literal("💰 Money: " + money), true); // true = action bar
            }
        });
    }

    // Show or update the money amount
    public static void setVisibleMoney(ServerPlayerEntity player, int money) {
        moneyMap.put(player, money);
    }

    // Stop showing money for a player
    public static void stopShowing(ServerPlayerEntity player) {
        moneyMap.remove(player);
    }
}
