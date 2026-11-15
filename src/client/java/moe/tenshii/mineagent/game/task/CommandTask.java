package moe.tenshii.mineagent.game.task;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import moe.tenshii.mineagent.config.AgentConfig;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Sends one server command as the player and collects the system messages that come back.
 */
public final class CommandTask implements Task {
	private static final int MAX_COMMAND_LENGTH = 256;
	private static final int MAX_WAIT_TICKS = 40;
	// Most commands answer in one message right away; stop listening once the replies pause this long.
	private static final int QUIET_TICKS = 6;
	private static final int MAX_REPLIES = 20;
	private static final int MAX_REPLY_CHARS = 2000;

	private static @Nullable CommandTask listening;

	private final String command;
	private final List<String> replies = new ArrayList<>();
	private boolean sent;
	private int ticksSinceSent;
	private int ticksSinceReply;

	public CommandTask(String command) throws TaskFailure {
		String text = command.strip();
		if (text.startsWith("/")) {
			text = text.substring(1).strip();
		}
		if (text.isEmpty()) {
			throw new TaskFailure("The command is empty.");
		}
		if (text.length() > MAX_COMMAND_LENGTH) {
			throw new TaskFailure("Commands can be at most " + MAX_COMMAND_LENGTH + " characters.");
		}
		if (text.chars().anyMatch(c -> c < ' ' || c == 127)) {
			throw new TaskFailure("The command contains line breaks or control characters; send one command per call.");
		}
		this.command = text;
	}

	/** Called for every system message the client receives. */
	public static void onGameMessage(Component message, boolean overlay) {
		CommandTask task = listening;
		if (task != null && task.sent && task.replies.size() < MAX_REPLIES) {
			task.replies.add((overlay ? "(action bar) " : "") + message.getString());
			task.ticksSinceReply = 0;
		}
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		if (!sent) {
			send(mc);
			return null;
		}
		ticksSinceSent++;
		ticksSinceReply++;
		boolean quiet = !replies.isEmpty() && ticksSinceReply >= QUIET_TICKS;
		if (quiet || ticksSinceSent >= MAX_WAIT_TICKS) {
			return summary();
		}
		return null;
	}

	private void send(Minecraft mc) throws TaskFailure {
		if (!AgentConfig.get().allowCommands) {
			throw new TaskFailure("Running commands is turned off. The person you work for can allow it with /agent commands on.");
		}
		LocalPlayer player = mc.player;
		ClientPacketListener connection = player.connection;
		if (!player.chatAbilities().canSendCommands()) {
			throw new TaskFailure("This player cannot send commands right now (chat or commands are restricted).");
		}
		String root = command.split(" ", 2)[0].toLowerCase(Locale.ROOT);
		CommandDispatcher<FabricClientCommandSource> clientCommands = ClientCommands.getActiveDispatcher();
		if (clientCommands != null && clientCommands.getRoot().getChild(root) != null) {
			throw new TaskFailure("/" + root + " is a client-side mod command, not a server command; run_command only sends server commands.");
		}
		CommandDispatcher<ClientSuggestionProvider> dispatcher = connection.getCommands();
		ParseResults<ClientSuggestionProvider> parse = dispatcher.parse(command, connection.getSuggestionsProvider());
		CommandSyntaxException error = Commands.getParseException(parse);
		if (error != null) {
			throw new TaskFailure("Not sent: " + error.getMessage() + ". The server only lists the commands this player may use,"
				+ " so an unknown command usually means no permission for it.");
		}
		if (parse.getContext().getLastChild().getCommand() == null) {
			throw new TaskFailure("Not sent: /" + command + " is incomplete; it needs more arguments.");
		}
		listening = this;
		sent = true;
		connection.sendCommand(command);
	}

	private String summary() {
		StringBuilder text = new StringBuilder("Sent /").append(command).append('.');
		if (replies.isEmpty()) {
			return text.append(" No reply from the server within ").append(MAX_WAIT_TICKS / 20)
				.append(" seconds; many commands succeed silently, so check the game state.").toString();
		}
		text.append(" Server messages since (other events may be mixed in):");
		int length = text.length();
		for (String reply : replies) {
			if (text.length() - length + reply.length() > MAX_REPLY_CHARS) {
				text.append("\n(more messages cut)");
				break;
			}
			text.append('\n').append(reply);
		}
		return text.toString();
	}

	@Override
	public void stop(Minecraft mc) {
		if (listening == this) {
			listening = null;
		}
	}

	@Override
	public int timeoutTicks() {
		return MAX_WAIT_TICKS + 20;
	}

	@Override
	public String describe() {
		return "running /" + command;
	}
}
