package noahs.money.mod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.scoreboard.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.advancement.AdvancementProgress;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static net.minecraft.server.command.CommandManager.*;

public class NoahsMoneyMod implements ModInitializer {

	private long lastKnownDay = -1;

	// Create a map to track players' rewarded advancements
	private static final Set<String> rewardedAdvancements = new HashSet<>();

	@Override
	public void onInitialize() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.getPlayer();
			Scoreboard scoreboard = server.getScoreboard();

			// Check if the "money" scoreboard objective exists
			ScoreboardObjective objective = scoreboard.getObjectives().stream()
					.filter(obj -> obj.getName().equals("money"))
					.findFirst()
					.orElse(null);

			// If the "money" objective does not exist, create it
			if (objective == null) {
				objective = scoreboard.addObjective(
						"money",
						ScoreboardCriterion.DUMMY,
						Text.literal("Money"),
						ScoreboardCriterion.RenderType.INTEGER,
						false,
						null
				);
			}
		});

		// Give 100 money to all players every Minecraft day
		ServerTickEvents.START_SERVER_TICK.register(server -> {
			long day = server.getOverworld().getTimeOfDay() / 24000;

			if (day != lastKnownDay) {
				lastKnownDay = day;

				for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
					try {
						addMoney(player, 100);
						player.sendMessage(Text.literal("You received ")
								.append(Text.literal("100 money").formatted(Formatting.GOLD))
								.append(" for a new day!"), false);
					} catch (Exception e) {
						System.err.println("Error giving money to player " + player.getName().getString() + ": " + e.getMessage());
					}
				}
			}
		});
		PersistentHud.start(); // Start showing HUD from server tick

		// Handle player joining
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.player;
			// Double check scoreboard exists before showing HUD
			Scoreboard scoreboard = server.getScoreboard();
			ScoreboardObjective objective = scoreboard.getObjectives().stream()
					.filter(obj -> obj.getName().equals("money"))
					.findFirst()
					.orElse(null);

			if (objective != null) {
				int money = getMoney(player);
				PersistentHud.setVisibleMoney(player, money);
			}
		});

		// Register commands
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			// /pay <target> <amount>
			dispatcher.register(literal("pay")
					.then(argument("target", EntityArgumentType.player())
							.then(argument("amount", IntegerArgumentType.integer(0))
									.executes(context -> {
										ServerPlayerEntity payer = context.getSource().getPlayer();
										ServerPlayerEntity recipient = EntityArgumentType.getPlayer(context, "target");
										int amount = IntegerArgumentType.getInteger(context, "amount");

										// Check if payer and recipient are the same
										if (payer.equals(recipient)) {
											context.getSource().sendFeedback(() -> Text.literal("You cannot pay yourself."), false);
											return 1;
										}

										// Get the payer's current money (if exists)
										int payerBalance = getMoney(payer);

										// Check if the payer has enough money
										if (payerBalance >= amount) {
											// Deduct money from payer and add it to recipient
											setMoney(payer, payerBalance - amount);
											addMoney(recipient, amount);

											context.getSource().sendFeedback(() -> Text.literal("You paid " + recipient.getName().getString() + " " + amount + " money."), false);
											recipient.sendMessage(Text.literal("You received " + amount + " money from " + payer.getName().getString() + "."), false);
										} else {
											context.getSource().sendFeedback(() -> Text.literal("You do not have enough money to pay " + recipient.getName().getString() + "."), false);
										}

										return 1;
									}))));

			// /setmoney <target> <amount>
			dispatcher.register(literal("setmoney")
					.requires(source -> source.hasPermissionLevel(2))
					.then(argument("targets", EntityArgumentType.players())
							.then(argument("amount", IntegerArgumentType.integer(0))
									.executes(context -> {
										Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(context, "targets");
										int amount = IntegerArgumentType.getInteger(context, "amount");

										for (ServerPlayerEntity target : targets) {
											setMoney(target, amount);
											context.getSource().sendFeedback(() -> Text.literal("Set money for " + target.getName().getString() + " to " + amount), false);
										}

										return 1;
									}))));

			// /initialisemoney
			dispatcher.register(literal("initialisemoney")
					.requires(source -> source.hasPermissionLevel(2))
					.executes(context -> {
						MinecraftServer server = context.getSource().getServer();
						runCommand(server, "scoreboard objectives add money dummy Money", context.getSource().getPlayer());
						context.getSource().sendFeedback(() -> Text.literal("Initialized money"), true);
						return 1;
					})
			);
		});
	}

	// Function to run a command as an entity or console
	public static void runCommand(MinecraftServer server, String command, Entity executor) {
		ServerCommandSource source;
		source = server.getCommandSource().withLevel(4);

		try {
			server.getCommandManager().executeWithPrefix(source, command);
		} catch (Exception e) {
			if (executor instanceof ServerPlayerEntity player) {
				player.sendMessage(Text.literal("Command failed: " + e.getMessage()).formatted(Formatting.RED), false);
			} else {
				System.err.println("Command failed: " + e.getMessage());
			}
		}
	}

	public static int getMoney(ServerPlayerEntity player) {
		MinecraftServer server = player.getServer();
		Scoreboard scoreboard = server.getScoreboard();

		// Check if the "money" scoreboard objective exists
		ScoreboardObjective objective = scoreboard.getObjectives().stream()
				.filter(obj -> obj.getName().equals("money"))
				.findFirst()
				.orElse(null);

		// If the "money" objective does not exist, run the /initialisemoney command to initialize it
		if (objective == null) {
			objective = scoreboard.addObjective(
					"money",                            // internal name
					ScoreboardCriterion.DUMMY,         // criterion
					Text.literal("Money"),             // display name
					ScoreboardCriterion.RenderType.INTEGER, // render type
					false,                             // usesRenderType (false = default scoreboard behavior)
					null                               // teamColor (null = default/no color)
			);

		}

		if (objective != null) {
			// If the player doesn't have a score, initialize it to 0
			ReadableScoreboardScore score = scoreboard.getScore(ScoreHolder.fromName(player.getName().getString()), objective);
			if (score == null) {
				scoreboard.getOrCreateScore(ScoreHolder.fromName(player.getName().getString()), objective).setScore(0);  // Initialize score to 0
			}

			// Return the player's current score
			return scoreboard.getScore(ScoreHolder.fromName(player.getName().getString()), objective).getScore();
		}

		// Return 0 if the "money" objective still doesn't exist after running the command
		return 0;
	}

	// Helper method to set the player's money on the scoreboard using commands
	public static void setMoney(ServerPlayerEntity player, int amount) {
		MinecraftServer server = player.getServer();

		// Set the scoreboard value
		String command = String.format("scoreboard players set %s money %d", player.getName().getString(), amount);
		runCommand(server, command, player);

		// Safely update HUD after scoreboard is changed
		server.execute(() -> {
			PersistentHud.setVisibleMoney(player, getMoney(player));
		});
	}

	// Helper method to add money to a player's score
	public static void addMoney(ServerPlayerEntity player, int amount) {
		int currentMoney = getMoney(player); // This could be problematic since we're running a command.
		setMoney(player, currentMoney + amount);
	}
}
