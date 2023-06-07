package org.broken.arrow.prompt.library;

import org.broken.arrow.prompt.library.utility.Validate;
import org.bukkit.conversations.ConversationAbandonedEvent;
import org.bukkit.conversations.ConversationContext;
import org.bukkit.conversations.Prompt;
import org.bukkit.conversations.ValidatingPrompt;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The SimplePrompt class is an abstract class that represents a simple command prompt in-game.
 * It allows you to create a chat-based interface for executing different types of commands.
 * This class provides an easier implementation of SimpleConversation.
 */
public abstract class SimplePrompt extends ValidatingPrompt implements Cloneable {

	/**
	 * Retrieves the prompt message to be displayed to the player.
	 *
	 * @param context The conversation context.
	 * @return The prompt message.
	 */
	protected abstract String getPrompt(ConversationContext context);

	private Player player = null;
	private final Plugin plugin;

	/**
	 * Constructs a SimplePrompt instance.
	 *
	 * @param plugin The plugin instance.
	 */
	public SimplePrompt(@Nonnull final Plugin plugin) {
		this.plugin = plugin;
	}

	/**
	 * Retrieves the prompt text to be displayed to the player.
	 *
	 * @param context The conversation context.
	 * @return The prompt text.
	 */
	@Nonnull
	@Override
	public final String getPromptText(@Nonnull final ConversationContext context) {
		return getPrompt(context);
	}

	/**
	 * Checks if the player input is valid.
	 *
	 * @param context The conversation context.
	 * @param input   The player input.
	 * @return True if the input is valid, false otherwise.
	 */
	@Override
	protected boolean isInputValid(@Nonnull final ConversationContext context, @Nullable final String input) {
		return true;
	}

	/**
	 * Starts a new SimpleConversation with the specified player.
	 *
	 * @param player The player to start the conversation with.
	 * @return The started SimpleConversation instance.
	 */
	public final SimpleConversation start(@Nonnull final Player player) {
		this.player = player;

		final SimpleConversation conversation = new SimpleConversation(this.plugin) {
			@Override
			public Prompt getFirstPrompt() {
				return SimplePrompt.this;
			}

		};
		conversation.start(player);
		return conversation;
	}

	/**
	 * Called when the whole conversation is over. This is called before {@link SimpleConversation#onConversationEnd(org.bukkit.conversations.ConversationAbandonedEvent)}
	 *
	 * @param conversation the message sent when end conversation.
	 * @param event        the event when conversation ends.
	 */
	public void onConversationEnd(final SimpleConversation conversation, final ConversationAbandonedEvent event) {
	}

	/**
	 * Converts the {@link org.bukkit.conversations.ConversationContext} into a {@link org.bukkit.entity.Player}
	 * or throws an error if it is not a player
	 *
	 * @param ctx conversation context.
	 * @return player in other case it will trow than error.
	 */
	protected final Player getPlayer(@Nonnull final ConversationContext ctx) {
		Validate.checkBoolean(!(ctx.getForWhom() instanceof Player), "Conversable is not a player but: " + ctx.getForWhom());

		return (Player) ctx.getForWhom();
	}

	@Nullable
	protected final Player getPlayer() {
		return this.player;
	}

	@Nullable
	@Override
	public final Prompt acceptInput(@Nonnull final ConversationContext context, final String input) {
		if (isInputValid(context, input))
			return acceptValidatedInput(context, input);

		else {
			final String failPrompt = getFailedValidationText(context, input);

			/*if (failPrompt != null)
				tellLater(1, context.getForWhom(), Variables.replace("&c" + failPrompt, getPlayer(context)));*/

			// Redisplay this prompt to the user to re-collect input
			return this;
		}
	}

	@Override
	public SimplePrompt clone() {
		try {
			// TODO: copy mutable state here, so the clone can't change the internals of the original
			return (SimplePrompt) super.clone();
		} catch (final CloneNotSupportedException e) {
			throw new AssertionError();
		}
	}

}
