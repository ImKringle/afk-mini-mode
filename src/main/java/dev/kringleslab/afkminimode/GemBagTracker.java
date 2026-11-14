/*
 * Copyright (c) 2022, TicTac7x
 * Copyright (c) 2026, serenibyss
 * All rights reserved.
 *
 * Gem bag/sack tracking for AFK Mini Mode, following the game messages and handling of the Gem Sack plugin
 * (https://github.com/serenibyss/osrs-gem-sack-plugin) and the Charges plugin
 * (https://github.com/TicTac7x/runelite-plugins/tree/plugin-charges).
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package dev.kringleslab.afkminimode;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Gem bag and gem sack: up to 60 of each gem type. The bar shows the fullest type, since that's the one that starts
 * spilling into the inventory. Message wording from the Gem Sack (serenibyss) and tictac7x charges plugins.
 */
public class GemBagTracker extends StorageTracker
{
	private static final int GAME_TICK_MARGIN = 2;
	private static final int PER_GEM = 60;

	// index order; the gem bag only takes the last five
	private static final String[] GEM_NAMES = {"Opal", "Jade", "Red topaz", "Sapphire", "Emerald", "Ruby", "Diamond", "Dragonstone"};
	private static final ImmutableList<Integer> GEM_IDS = ImmutableList.of(
		ItemID.UNCUT_OPAL, ItemID.UNCUT_JADE, ItemID.UNCUT_RED_TOPAZ, ItemID.UNCUT_SAPPHIRE,
		ItemID.UNCUT_EMERALD, ItemID.UNCUT_RUBY, ItemID.UNCUT_DIAMOND, ItemID.UNCUT_DRAGONSTONE);

	private static final String BAG = "gem (?:bag|sack)";
	private static final String TELEGRAB_PREFIX = "Telekinetic Grab -> ";
	// "Sapphires: 12 / Emeralds: 3 / ..." (gem sack also has an "Opal: 0 / Jade: 0 / Red Topaz: 0" part)
	private static final Pattern CHECK_ENTRY = Pattern.compile("(Opal|Jade|Red Topaz|Sapphires|Emeralds|Rubies|Diamonds|Dragonstones): (\\d+)");
	private static final Pattern EMPTY_PATTERN = Pattern.compile(
		"^(?:The " + BAG + " is (?:now )?empty\\.|You empty (?:the|your) " + BAG + " into the bank\\.)$");
	private static final Pattern MINED_PATTERN = Pattern.compile("^You just (?:found|mined) an? (.+)!$");
	private static final Pattern STOLEN_PATTERN = Pattern.compile(
		"^(?:The following stolen loot gets added to your " + BAG + ": Uncut (.+) x (\\d+)"
			+ "|You put the stolen Uncut (.+) into your " + BAG
			+ "|You steal an uncut (.+) and add it to your " + BAG + ")\\.$");

	private static final ImmutableList<Integer> BAG_IDS = ImmutableList.of(
		ItemID.GEM_BAG, ItemID.GEM_BAG_OPEN, ItemID.GEM_SACK, ItemID.GEM_SACK_OPEN);
	private static final ImmutableList<Integer> SACK_IDS = ImmutableList.of(ItemID.GEM_SACK, ItemID.GEM_SACK_OPEN);
	private static final int FIRST_BAG_GEM = 3; // sapphire
	private static final ImmutableList<Integer> OPEN_BAG_IDS = ImmutableList.of(ItemID.GEM_BAG_OPEN, ItemID.GEM_SACK_OPEN);

	private final int[] gems = new int[GEM_NAMES.length];
	// gem the last click was a Take or Telegrab of, -1 otherwise; an open bag catches it (as in the Gem Sack plugin)
	private int pendingPickup = -1;

	@Inject
	GemBagTracker(Client client, ClientThread clientThread)
	{
		// no check dialogue: the gem bag reports in chat
		super(client, clientThread, "gem bag", GAME_TICK_MARGIN, BAG_IDS, OPEN_BAG_IDS, text -> -1);
	}

	@Override
	public int getCapacity()
	{
		return PER_GEM;
	}

	@Override
	public String tooltipDetail()
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < gems.length; i++)
		{
			if (gems[i] > 0)
			{
				sb.append(sb.length() == 0 ? "" : "</br>").append(GEM_NAMES[i]).append(": ").append(gems[i]).append(" / ").append(PER_GEM);
			}
		}
		return sb.length() == 0 ? "Empty" : sb.toString();
	}

	@Override
	protected void reset()
	{
		super.reset();
		Arrays.fill(gems, 0);
		pendingPickup = -1;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if ((event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM) || !hasContainer())
		{
			return;
		}
		String message = event.getMessage();

		int[] checked = parseCheck(message);
		if (checked != null)
		{
			// exact counts (e.g. "Left in bag: ..." after Empty) already include a pending fill/empty, so don't diff it again
			consumeAction(Action.FILL);
			consumeAction(Action.EMPTY);
			for (int i = 0; i < gems.length; i++)
			{
				if (checked[i] != -1)
				{
					gems[i] = checked[i];
				}
			}
			// the gem bag's line starts with sapphires; that's the reading that covers every gem the bag takes
			if (checked[FIRST_BAG_GEM] != -1)
			{
				sync();
			}
			return;
		}
		if (EMPTY_PATTERN.matcher(message).matches()
			|| message.equals(EMPTIED_CONTAINERS_TO_BANK_MESSAGE) || message.equals(CONTAINERS_ALREADY_EMPTY_MESSAGE))
		{
			Arrays.fill(gems, 0);
			sync();
			return;
		}
		// below: gems that land straight in an open bag, never touching the inventory
		if (!isCollecting())
		{
			return;
		}
		Matcher m = MINED_PATTERN.matcher(message);
		if (m.matches())
		{
			add(gemIndex(m.group(1)), 1);
			return;
		}
		m = STOLEN_PATTERN.matcher(message);
		if (m.matches())
		{
			// group 1+2: pickpocket loot with a count; group 3 or 4: a single stolen gem
			if (m.group(1) != null)
			{
				add(gemIndex(m.group(1)), Integer.parseInt(m.group(2)));
			}
			else
			{
				add(gemIndex(m.group(3) != null ? m.group(3) : m.group(4)), 1);
			}
		}
	}

	// Fill/Empty by hand don't report counts, so the moved gems are read off the inventory diff
	@Override
	protected void inventoryChanged(Multiset<Integer> prev)
	{
		if (consumeAction(Action.FILL))
		{
			// gems that left the inventory went into the bag
			Multiset<Integer> moved = Multisets.difference(prev, inventory);
			for (int i = 0; i < gems.length; i++)
			{
				add(i, moved.count(GEM_IDS.get(i)));
			}
		}
		else if (consumeAction(Action.EMPTY))
		{
			Multiset<Integer> moved = Multisets.difference(inventory, prev);
			for (int i = 0; i < gems.length; i++)
			{
				add(i, -moved.count(GEM_IDS.get(i)));
			}
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// any other click cancels a pending pickup, so a later despawn (someone else's pickup) isn't counted
		String target = Text.removeTags(event.getMenuTarget());
		boolean pickup = "Take".equals(event.getMenuOption())
			|| "Cast".equals(event.getMenuOption()) && target.startsWith(TELEGRAB_PREFIX);
		pendingPickup = pickup ? gemIndex(target.replace(TELEGRAB_PREFIX, "")) : -1;

		Widget w = event.getWidget();
		if (w == null || !BAG_IDS.contains(w.getItemId()))
		{
			return;
		}
		switch (event.getMenuAction())
		{
			case WIDGET_TARGET_ON_WIDGET:
				// using gems on the bag
				markAction(Action.FILL);
				return;
			case CC_OP:
			case CC_OP_LOW_PRIORITY:
				break;
			default:
				return;
		}
		switch (event.getMenuOption().toLowerCase())
		{
			case "fill":
				markAction(Action.FILL);
				break;
			case "empty":
				markAction(Action.EMPTY);
				break;
		}
	}

	// a gem picked up into an open bag only shows as the ground item vanishing
	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		if (pendingPickup != -1 && event.getItem().getId() == GEM_IDS.get(pendingPickup) && isCollecting())
		{
			add(pendingPickup, 1);
			pendingPickup = -1;
		}
	}

	private void add(int gem, int count)
	{
		// the gem bag (not the sack) has no room for opal, jade or red topaz
		if (gem == -1 || count == 0 || !isKnown() || (gem < FIRST_BAG_GEM && !has(SACK_IDS)))
		{
			return;
		}
		gems[gem] = Math.max(0, Math.min(PER_GEM, gems[gem] + count));
		sync();
	}

	// the base class tracks one number: the fullest gem, which is what the bar shows
	private void sync()
	{
		setExact(Arrays.stream(gems).max().orElse(0));
	}

	/** "sapphire", "Uncut Sapphire", "Rubies", "red topaz"... -> index, or -1 */
	static int gemIndex(String name)
	{
		String n = name.toLowerCase().replace("uncut ", "").trim();
		if (n.equals("rubies"))
		{
			return 5;
		}
		for (int i = 0; i < GEM_NAMES.length; i++)
		{
			String g = GEM_NAMES[i].toLowerCase();
			if (n.equals(g) || n.equals(g + "s"))
			{
				return i;
			}
		}
		return -1;
	}

	/** @return per-gem counts from a check message (-1 for gems it doesn't mention), or null if it isn't one */
	static int[] parseCheck(String text)
	{
		Matcher m = CHECK_ENTRY.matcher(text);
		int[] counts = null;
		while (m.find())
		{
			if (counts == null)
			{
				counts = new int[GEM_NAMES.length];
				Arrays.fill(counts, -1);
			}
			counts[gemIndex(m.group(1))] = Integer.parseInt(m.group(2));
		}
		return counts;
	}
}
