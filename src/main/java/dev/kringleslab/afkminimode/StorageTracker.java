package dev.kringleslab.afkminimode;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.ToIntFunction;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
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
	public static final int CAPACITY = 28;

	protected enum Action
	{
		FILL, EMPTY, CHECK
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
	protected final Multiset<Integer> inventory = HashMultiset.create();
	protected final Multiset<Integer> equipment = HashMultiset.create();
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

	/** Called with the previous inventory after it changed, only while a container is carried. */
	protected abstract void inventoryChanged(Multiset<Integer> prev);

	void start()
	{
		reset();
		clientThread.invokeLater(this::refreshSnapshots);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::refreshSnapshots);
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			reset();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INV)
		{
			Multiset<Integer> prev = HashMultiset.create(inventory);
			snapshot(event.getItemContainer(), inventory);
			if (hasContainer())
			{
				inventoryChanged(prev);
			}
		}
		else if (event.getContainerId() == InventoryID.WORN)
		{
			snapshot(event.getItemContainer(), equipment);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
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

	protected boolean hasContainer()
	{
		return has(allIds);
	}

	protected void setExact(int count)
	{
		held = Math.max(0, Math.min(CAPACITY, count));
		known = true;
	}

	protected void addIfKnown(int count)
	{
		if (known)
		{
			held = Math.max(0, Math.min(CAPACITY, held + count));
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

	private static void snapshot(ItemContainer container, Multiset<Integer> target)
	{
		target.clear();
		for (Item i : container.getItems())
		{
			target.add(i.getId());
		}
	}

	private void refreshSnapshots()
	{
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (inv != null)
		{
			snapshot(inv, inventory);
		}
		if (worn != null)
		{
			snapshot(worn, equipment);
		}
	}

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
		inventory.clear();
		equipment.clear();
		actionTicks.clear();
	}
}
