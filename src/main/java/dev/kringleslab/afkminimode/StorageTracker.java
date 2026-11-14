package dev.kringleslab.afkminimode;

import com.google.common.collect.Multiset;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.ToIntFunction;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Count of items in a skilling container (fish barrel, log basket...) that the game never sends to the client.
 * The count is only "known" after an exact reading: a Check, or a full/empty message. Increments on top of an
 * unknown count are dropped, so the status bar never shows a guess.
 * Subclasses must not redeclare the @Subscribe methods here (RuneLite's EventBus rejects that).
 */
public abstract class StorageTracker
{
	private static final int DEFAULT_CAPACITY = 28;
	// bank "Empty containers" button, shared by every container
	protected static final String EMPTIED_CONTAINERS_TO_BANK_MESSAGE = "You empty all of your containers into the bank.";
	protected static final String CONTAINERS_ALREADY_EMPTY_MESSAGE = "Your containers are already empty.";

	protected enum Action
	{
		FILL, EMPTY, CHECK, SUPERHEAT
	}

	@Getter
	private final String name;
	private final Collection<Integer> allIds;
	private final Collection<Integer> openIds;
	private final int tickMargin;
	// Check dialogue text -> count, or -1 if unreadable
	private final ToIntFunction<String> checkParser;

	@Getter
	private int held;
	@Getter
	private boolean known;

	protected final Client client;
	protected final ClientThread clientThread;
	// item ID snapshots shared by all trackers, kept current by the plugin
	protected Multiset<Integer> inventory;
	protected Multiset<Integer> equipment;
	private final Map<Action, Integer> actionTicks = new EnumMap<>(Action.class);

	protected StorageTracker(Client client, ClientThread clientThread, String name, int tickMargin,
		Collection<Integer> allIds, Collection<Integer> openIds, ToIntFunction<String> checkParser)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.name = name;
		this.tickMargin = tickMargin;
		this.allIds = allIds;
		this.openIds = openIds;
		this.checkParser = checkParser;
	}

	/** Called by the plugin with the previous inventory after it changed, only while a container is carried. */
	protected void inventoryChanged(Multiset<Integer> prev)
	{
	}

	/** Called by the plugin on startUp with its shared inventory/equipment snapshots. */
	void start(Multiset<Integer> inventory, Multiset<Integer> equipment)
	{
		this.inventory = inventory;
		this.equipment = equipment;
		reset();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			// contents can change while logged out (other account, mobile), so the count is no longer exact
			reset();
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		// a Check opens the item dialogue box; its text is only filled in after WidgetLoaded, hence invokeLater
		if (event.getGroupId() == InterfaceID.OBJECTBOX && consumeAction(Action.CHECK))
		{
			clientThread.invokeLater(() ->
			{
				Widget w = client.getWidget(InterfaceID.Objectbox.TEXT);
				int count = w == null ? -1 : checkParser.applyAsInt(w.getText());
				if (count != -1)
				{
					setExact(count);
				}
			});
		}
	}

	/** Open container in the inventory or worn, so new items flow into it. */
	public boolean isCollecting()
	{
		return collectingItemId() != -1;
	}

	/** Item ID of the open container being carried (for its icon), or -1. */
	public int collectingItemId()
	{
		for (int id : openIds)
		{
			if (inventory.contains(id) || equipment.contains(id))
			{
				return id;
			}
		}
		return -1;
	}

	/** Max count; can depend on gear (coal bag + Smithing cape), so it's read live rather than stored. */
	public int getCapacity()
	{
		return DEFAULT_CAPACITY;
	}

	/** Tooltip body replacing the default "Holding x / y" lines (joined with </br>), or null for the default. */
	public String tooltipDetail()
	{
		return null;
	}

	protected boolean hasContainer()
	{
		return has(allIds);
	}

	protected void setExact(int count)
	{
		held = Math.max(0, Math.min(getCapacity(), count));
		known = true;
	}

	protected void addIfKnown(int count)
	{
		if (known)
		{
			held = Math.max(0, Math.min(getCapacity(), held + count));
		}
	}

	protected void forget()
	{
		held = 0;
		known = false;
	}

	protected boolean has(Collection<Integer> ids)
	{
		for (int id : ids)
		{
			if (inventory.contains(id) || equipment.contains(id))
			{
				return true;
			}
		}
		return false;
	}

	// a click and its result (chat, inventory change) land a tick or two apart; actions bridge that gap
	protected void markAction(Action action)
	{
		actionTicks.put(action, client.getTickCount());
	}

	/** True (once) if the action was clicked within the last tickMargin ticks. */
	protected boolean consumeAction(Action action)
	{
		Integer tick = actionTicks.get(action);
		if (tick != null && tick > client.getTickCount() - tickMargin)
		{
			actionTicks.remove(action);
			return true;
		}
		return false;
	}

	protected void reset()
	{
		forget();
		actionTicks.clear();
	}
}
